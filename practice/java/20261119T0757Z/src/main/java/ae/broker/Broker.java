package ae.broker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * In-process partitioned log with Kafka-style consumer groups. See TASK.md for the full contract.
 * All public methods are synchronized on the broker; time comes from the injected clock.
 */
public final class Broker {
    /** Where a produced record landed. */
    public record Metadata(int partition, long offset) { }

    /** One delivered record. */
    public record Message(String topic, int partition, long offset, String key, String value, long timestamp) { }

    /** A member's view of its group: the generation and its sorted partition assignment. */
    public record Membership(int generation, List<TopicPartition> assignment) { }

    private static final class Partition {
        final List<Message> msgs = new ArrayList<>();
        long start;

        long end() { return start + msgs.size(); }
    }

    private static final class Topic {
        final Partition[] partitions;
        int roundRobin;

        Topic(int n) {
            partitions = new Partition[n];
            for (int i = 0; i < n; i++) partitions[i] = new Partition();
        }
    }

    private static final class Group {
        int generation;
        final Map<String, Set<String>> subscriptions = new TreeMap<>();
        final Map<String, Long> lastSeen = new HashMap<>();
        final Map<String, List<TopicPartition>> assignment = new TreeMap<>();
        final Map<TopicPartition, Long> committed = new HashMap<>();
        final Map<TopicPartition, Long> position = new HashMap<>();
    }

    private final LongSupplier clock;
    private final long sessionTimeoutMs;
    private final Map<String, Topic> topics = new HashMap<>();
    private final Map<String, Group> groups = new HashMap<>();

    public Broker(LongSupplier clockMillis, long sessionTimeoutMs) {
        this.clock = Objects.requireNonNull(clockMillis, "clock");
        if (sessionTimeoutMs <= 0) throw new IllegalArgumentException("sessionTimeoutMs must be > 0");
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    // ---- topics and logs -------------------------------------------------------------------

    public synchronized void createTopic(String name, int partitions) {
        Objects.requireNonNull(name, "name");
        if (partitions < 1) throw new IllegalArgumentException("partitions must be >= 1");
        if (topics.containsKey(name)) throw new IllegalArgumentException("topic exists: " + name);
        topics.put(name, new Topic(partitions));
    }

    public synchronized Metadata produce(String topic, String key, String value) {
        Topic t = topic(topic);
        int p;
        if (key != null) {
            p = Math.floorMod(key.hashCode(), t.partitions.length);
        } else {
            p = t.roundRobin;
            t.roundRobin = (t.roundRobin + 1) % t.partitions.length;
        }
        Partition part = t.partitions[p];
        long offset = part.end();
        part.msgs.add(new Message(topic, p, offset, key, value, clock.getAsLong()));
        return new Metadata(p, offset);
    }

    public synchronized long logStart(String topic, int partition) { return part(topic, partition).start; }

    public synchronized long logEnd(String topic, int partition) { return part(topic, partition).end(); }

    /** Drops the oldest records so at most {@code maxRecords} remain; offsets are never reused. */
    public synchronized void retain(String topic, int partition, int maxRecords) {
        if (maxRecords < 0) throw new IllegalArgumentException("maxRecords < 0");
        Partition p = part(topic, partition);
        int drop = p.msgs.size() - maxRecords;
        if (drop > 0) {
            p.msgs.subList(0, drop).clear();
            p.start += drop;
        }
    }

    // ---- groups ----------------------------------------------------------------------------

    public synchronized Membership join(String group, String member, Set<String> subscribe) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(member, "member");
        if (subscribe == null || subscribe.isEmpty()) throw new IllegalArgumentException("no topics");
        for (String t : subscribe) topic(t);
        Group g = groups.computeIfAbsent(group, k -> new Group());
        sweep(g);
        Set<String> subs = new TreeSet<>(subscribe);
        boolean unchanged = subs.equals(g.subscriptions.get(member));
        g.subscriptions.put(member, subs);
        g.lastSeen.put(member, clock.getAsLong());
        if (!unchanged) rebalance(g);
        return membership(g, member);
    }

    public synchronized void leave(String group, String member) {
        Group g = liveGroup(group);
        requireMember(g, member);
        g.subscriptions.remove(member);
        g.lastSeen.remove(member);
        rebalance(g);
    }

    public synchronized Membership heartbeat(String group, String member) {
        Group g = liveGroup(group);
        requireMember(g, member);
        g.lastSeen.put(member, clock.getAsLong());
        return membership(g, member);
    }

    public synchronized List<Message> poll(String group, String member, int generation, int max) {
        if (max < 1) throw new IllegalArgumentException("max must be >= 1");
        Group g = liveGroup(group);
        requireMember(g, member);
        requireGeneration(g, generation);
        g.lastSeen.put(member, clock.getAsLong());
        List<Message> out = new ArrayList<>();
        for (TopicPartition tp : g.assignment.get(member)) {
            if (out.size() >= max) break;
            Partition p = part(tp.topic(), tp.partition());
            long pos = position(g, tp, p);
            while (pos < p.end() && out.size() < max) {
                out.add(p.msgs.get((int) (pos - p.start)));
                pos++;
            }
            g.position.put(tp, pos);
        }
        return out;
    }

    public synchronized void commit(String group, String member, int generation, TopicPartition tp, long nextOffset) {
        Group g = liveGroup(group);
        requireMember(g, member);
        requireGeneration(g, generation);
        if (!g.assignment.get(member).contains(tp)) {
            throw new IllegalStateException(tp + " is not assigned to " + member);
        }
        long pos = position(g, tp, part(tp.topic(), tp.partition()));
        if (nextOffset < 0 || nextOffset > pos) {
            throw new IllegalArgumentException("offset " + nextOffset + " outside [0, " + pos + "]");
        }
        g.lastSeen.put(member, clock.getAsLong());
        g.committed.put(tp, nextOffset);
    }

    public synchronized long committed(String group, TopicPartition tp) {
        Group g = groups.get(group);
        return g == null ? -1 : g.committed.getOrDefault(tp, -1L);
    }

    public synchronized long lag(String group) {
        Group g = groups.get(group);
        if (g == null) return 0;
        sweep(g);
        Set<String> subscribed = new TreeSet<>();
        g.subscriptions.values().forEach(subscribed::addAll);
        long lag = 0;
        for (String t : subscribed) {
            Partition[] ps = topics.get(t).partitions;
            for (int i = 0; i < ps.length; i++) {
                long from = Math.max(g.committed.getOrDefault(new TopicPartition(t, i), ps[i].start), ps[i].start);
                lag += ps[i].end() - from;
            }
        }
        return lag;
    }

    /** Read-only member to assignment view; never refreshes sessions or evicts anyone. */
    public synchronized Map<String, List<TopicPartition>> assignments(String group) {
        Map<String, List<TopicPartition>> out = new TreeMap<>();
        Group g = groups.get(group);
        if (g != null) g.assignment.forEach((m, l) -> out.put(m, List.copyOf(l)));
        return out;
    }

    // ---- internals -------------------------------------------------------------------------

    private Topic topic(String name) {
        Topic t = topics.get(name);
        if (t == null) throw new IllegalArgumentException("unknown topic: " + name);
        return t;
    }

    private Partition part(String topic, int partition) {
        Topic t = topic(topic);
        if (partition < 0 || partition >= t.partitions.length) {
            throw new IllegalArgumentException("bad partition " + partition + " for " + topic);
        }
        return t.partitions[partition];
    }

    private Group liveGroup(String group) {
        Group g = groups.get(group);
        if (g == null) throw new UnknownMemberException("unknown group: " + group);
        sweep(g);
        return g;
    }

    private static void requireMember(Group g, String member) {
        if (!g.subscriptions.containsKey(member)) throw new UnknownMemberException("unknown member: " + member);
    }

    private static void requireGeneration(Group g, int generation) {
        if (generation != g.generation) {
            throw new StaleGenerationException("generation " + generation + " != " + g.generation);
        }
    }

    private static long position(Group g, TopicPartition tp, Partition p) {
        Long pos = g.position.get(tp);
        long base = pos != null ? pos : g.committed.getOrDefault(tp, p.start);
        return Math.max(base, p.start);
    }

    /** Evicts every silent member in one rebalance (one generation bump, however many left). */
    private void sweep(Group g) {
        long now = clock.getAsLong();
        boolean evicted = false;
        for (Iterator<Map.Entry<String, Long>> it = g.lastSeen.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > sessionTimeoutMs) {
                g.subscriptions.remove(e.getKey());
                it.remove();
                evicted = true;
            }
        }
        if (evicted) rebalance(g);
    }

    private void rebalance(Group g) {
        g.generation++;
        g.position.clear();
        g.assignment.clear();
        Map<String, List<TopicPartition>> next = new TreeMap<>();
        for (String m : g.subscriptions.keySet()) next.put(m, new ArrayList<>());
        Set<String> all = new TreeSet<>();
        g.subscriptions.values().forEach(all::addAll);
        for (String t : all) {
            List<String> subscribers = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : g.subscriptions.entrySet()) {
                if (e.getValue().contains(t)) subscribers.add(e.getKey()); // TreeMap: sorted by member id
            }
            int n = topics.get(t).partitions.length;
            for (int i = 0; i < n; i++) next.get(subscribers.get(i % subscribers.size())).add(new TopicPartition(t, i));
        }
        g.assignment.putAll(next);
    }

    private Membership membership(Group g, String member) {
        return new Membership(g.generation, List.copyOf(g.assignment.get(member)));
    }
}
