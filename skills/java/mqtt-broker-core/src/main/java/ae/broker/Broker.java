package ae.broker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * In-process MQTT-style broker core: sessions, wildcard subscriptions, retained messages, QoS 0/1 with
 * acknowledgements and redelivery, will messages. Single-threaded; callers synchronise externally.
 */
public final class Broker {
    /** Broker limits. */
    public record Config(int maxInflight, int maxQueued, long retryMs, long sessionExpiryMs) {
        public Config {
            if (maxInflight < 1 || maxInflight > 65535) {
                throw new IllegalArgumentException("maxInflight must be 1..65535");
            }
            if (maxQueued < 0 || retryMs < 1 || sessionExpiryMs < 0) {
                throw new IllegalArgumentException("bad limits");
            }
        }
    }

    /** A message published on abnormal disconnect. */
    public record Will(String topic, String payload, int qos, boolean retain) {
        public Will {
            Topics.validateTopic(topic);
            checkQos(qos);
            if (payload == null) {
                throw new IllegalArgumentException("payload");
            }
        }
    }

    private record Msg(String topic, String payload, int qos, boolean retain) {}

    private static final class Inflight {
        final Msg msg;
        long sentAt;

        Inflight(Msg msg, long sentAt) {
            this.msg = msg;
            this.sentAt = sentAt;
        }
    }

    private static final class Session {
        final String id;
        final boolean clean;
        boolean connected;
        Will will;
        long offlineSince;
        int nextPacketId = 1;
        final Map<String, Integer> subs = new LinkedHashMap<>();
        final Map<Integer, Inflight> inflight = new LinkedHashMap<>();
        final Deque<Msg> pending = new ArrayDeque<>();
        final List<Delivery> outbox = new ArrayList<>();

        Session(String id, boolean clean) {
            this.id = id;
            this.clean = clean;
        }
    }

    private final LongSupplier clock;
    private final Config config;
    private final Map<String, Session> sessions = new TreeMap<>();
    private final SubscriptionTrie<Integer> trie = new SubscriptionTrie<>();
    private final Map<String, Msg> retained = new TreeMap<>();
    private long dropped;

    public Broker(LongSupplier clock, Config config) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.config = java.util.Objects.requireNonNull(config);
    }

    private static void checkQos(int qos) {
        if (qos != 0 && qos != 1) {
            throw new IllegalArgumentException("qos must be 0 or 1: " + qos);
        }
    }

    // ---- connection lifecycle ----

    /** Connects a client; returns true when a stored persistent session was resumed. */
    public boolean connect(String id, boolean clean, Will will) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("client id must be non-empty");
        }
        Session old = sessions.get(id);
        if (old != null && old.connected) {
            dropConnection(old);
            old = sessions.get(id);
        }
        if (clean && old != null) {
            eraseSession(old);
            old = null;
        }
        boolean present = old != null;
        Session s = old != null ? old : new Session(id, clean);
        sessions.put(id, s);
        s.connected = true;
        s.will = will;
        if (present) {
            long now = clock.getAsLong();
            for (Map.Entry<Integer, Inflight> e : s.inflight.entrySet()) {
                e.getValue().sentAt = now;
                s.outbox.add(toDelivery(e.getKey(), e.getValue().msg, true));
            }
            fillWindow(s);
        }
        return present;
    }

    /** Graceful disconnect: the will is discarded. */
    public void disconnect(String id) {
        Session s = sessions.get(id);
        if (s != null && s.connected) {
            s.will = null;
            dropConnection(s);
        }
    }

    /** Abnormal disconnect: the will, if any, is published. */
    public void drop(String id) {
        Session s = sessions.get(id);
        if (s != null && s.connected) {
            dropConnection(s);
        }
    }

    private void dropConnection(Session s) {
        Will w = s.will;
        s.will = null;
        s.connected = false;
        s.outbox.clear();
        s.offlineSince = clock.getAsLong();
        if (s.clean) {
            eraseSession(s);
        }
        if (w != null) {
            publish(w.topic(), w.payload(), w.qos(), w.retain());
        }
    }

    private void eraseSession(Session s) {
        trie.removeKey(s.id);
        sessions.remove(s.id);
    }

    // ---- subscriptions ----

    public void subscribe(String id, String filter, int qos) {
        Topics.validateFilter(filter);
        checkQos(qos);
        Session s = connectedSession(id);
        trie.add(filter, id, qos);
        s.subs.put(filter, qos);
        for (Map.Entry<String, Msg> e : retained.entrySet()) {
            if (Topics.matches(filter, e.getKey())) {
                Msg m = e.getValue();
                deliver(s, new Msg(m.topic(), m.payload(), Math.min(m.qos(), qos), true));
            }
        }
    }

    public boolean unsubscribe(String id, String filter) {
        Session s = sessions.get(id);
        if (s == null || s.subs.remove(filter) == null) {
            return false;
        }
        trie.remove(filter, id);
        return true;
    }

    private Session connectedSession(String id) {
        Session s = sessions.get(id);
        if (s == null || !s.connected) {
            throw new IllegalStateException("client not connected: " + id);
        }
        return s;
    }

    // ---- publishing ----

    public void publish(String topic, String payload, int qos, boolean retain) {
        Topics.validateTopic(topic);
        checkQos(qos);
        if (payload == null) {
            throw new IllegalArgumentException("payload");
        }
        if (retain) {
            if (payload.isEmpty()) {
                retained.remove(topic);
            } else {
                retained.put(topic, new Msg(topic, payload, qos, true));
            }
        }
        Map<String, Integer> best = new TreeMap<>();
        for (SubscriptionTrie.Entry<Integer> e : trie.match(topic)) {
            best.merge(e.key(), e.value(), Math::max);
        }
        for (Map.Entry<String, Integer> e : best.entrySet()) {
            Session s = sessions.get(e.getKey());
            deliver(s, new Msg(topic, payload, Math.min(qos, e.getValue()), false));
        }
    }

    private void deliver(Session s, Msg m) {
        if (m.qos() == 0) {
            if (s.connected) {
                s.outbox.add(new Delivery(m.topic(), m.payload(), 0, m.retain(), false, 0));
            }
            return;
        }
        if (s.connected && s.inflight.size() < config.maxInflight()) {
            send(s, m);
        } else {
            if (s.pending.size() >= config.maxQueued()) {
                dropped++;
                if (s.pending.isEmpty()) {
                    return;
                }
                s.pending.pollFirst();
            }
            s.pending.addLast(m);
        }
    }

    private void send(Session s, Msg m) {
        int id = nextFreeId(s);
        s.inflight.put(id, new Inflight(m, clock.getAsLong()));
        s.outbox.add(toDelivery(id, m, false));
    }

    private static Delivery toDelivery(int id, Msg m, boolean dup) {
        return new Delivery(m.topic(), m.payload(), m.qos(), m.retain(), dup, id);
    }

    private static int nextFreeId(Session s) {
        int id = s.nextPacketId;
        while (s.inflight.containsKey(id)) {
            id = id == 65535 ? 1 : id + 1;
        }
        s.nextPacketId = id == 65535 ? 1 : id + 1;
        return id;
    }

    private void fillWindow(Session s) {
        while (s.connected && s.inflight.size() < config.maxInflight() && !s.pending.isEmpty()) {
            Msg m = s.pending.pollFirst();
            send(s, m);
        }
    }

    // ---- acknowledgements, time ----

    /** Client acknowledged a QoS 1 packet. Returns false if the id is unknown. */
    public boolean ack(String id, int packetId) {
        Session s = sessions.get(id);
        if (s == null || s.inflight.remove(packetId) == null) {
            return false;
        }
        fillWindow(s);
        return true;
    }

    /** Resends overdue unacked messages and expires long-offline persistent sessions. */
    public void tick() {
        long now = clock.getAsLong();
        List<Session> expired = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (s.connected) {
                for (Map.Entry<Integer, Inflight> e : s.inflight.entrySet()) {
                    Inflight f = e.getValue();
                    if (now - f.sentAt >= config.retryMs()) {
                        f.sentAt = now;
                        s.outbox.add(toDelivery(e.getKey(), f.msg, true));
                    }
                }
            } else if (now - s.offlineSince >= config.sessionExpiryMs()) {
                expired.add(s);
            }
        }
        for (Session s : expired) {
            eraseSession(s);
        }
    }

    /** Returns and clears the client's outgoing deliveries. */
    public List<Delivery> drain(String id) {
        Session s = sessions.get(id);
        if (s == null || s.outbox.isEmpty()) {
            return List.of();
        }
        List<Delivery> out = new ArrayList<>(s.outbox);
        s.outbox.clear();
        return out;
    }

    // ---- introspection ----

    public boolean hasSession(String id) {
        return sessions.containsKey(id);
    }

    public int retainedCount() {
        return retained.size();
    }

    public int subscriptionCount() {
        return trie.size();
    }

    public int inflightCount(String id) {
        Session s = sessions.get(id);
        return s == null ? 0 : s.inflight.size();
    }

    public int pendingCount(String id) {
        Session s = sessions.get(id);
        return s == null ? 0 : s.pending.size();
    }

    public long droppedCount() {
        return dropped;
    }

    /** Throws AssertionError when an internal invariant is violated. */
    void checkInvariants() {
        int subTotal = 0;
        for (Session s : sessions.values()) {
            check(s.inflight.size() <= config.maxInflight(), "window exceeded for " + s.id);
            check(s.pending.size() <= config.maxQueued(), "pending cap exceeded for " + s.id);
            check(s.connected || s.outbox.isEmpty(), "offline outbox for " + s.id);
            check(s.connected || s.will == null, "offline session holds will: " + s.id);
            check(s.connected || !s.clean, "offline clean session " + s.id);
            for (Map.Entry<Integer, Inflight> e : s.inflight.entrySet()) {
                check(e.getKey() >= 1 && e.getKey() <= 65535, "packet id range");
                check(e.getValue().msg.qos() == 1, "qos0 inflight");
            }
            for (Msg m : s.pending) {
                check(m.qos() == 1, "qos0 pending");
            }
            for (Map.Entry<String, Integer> e : s.subs.entrySet()) {
                boolean found = false;
                for (SubscriptionTrie.Entry<Integer> t : trie.match(sampleTopic(e.getKey()))) {
                    if (t.key().equals(s.id) && t.value().equals(e.getValue())) {
                        found = true;
                    }
                }
                check(found, "session sub missing from trie: " + s.id + " " + e.getKey());
            }
            subTotal += s.subs.size();
        }
        check(subTotal == trie.size(), "trie size " + trie.size() + " != session subs " + subTotal);
    }

    /** A topic guaranteed to match {@code filter}: wildcards replaced by a plain level. */
    private static String sampleTopic(String filter) {
        String[] levels = filter.split("/", -1);
        List<String> out = new ArrayList<>();
        for (String l : levels) {
            if (l.equals("#")) {
                break;
            }
            out.add(l.equals("+") ? "x" : l);
        }
        return out.isEmpty() ? "x" : String.join("/", out);
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }
}
