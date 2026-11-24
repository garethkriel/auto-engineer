package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Seeded random simulation: invariant checker after every step plus a delivery oracle. */
class BrokerSimulationTest {
    private static final String[] WORDS = {"a", "b", "c"};
    private static final int CLIENTS = 4;

    /** Oracle of what each persistent QoS 1 client must eventually see, in order. */
    static final class Client {
        final String id;
        boolean connected;
        // filter -> qos for the oracle (only persistent sessions with subs)
        final Map<String, Integer> subs = new LinkedHashMap<>();
        // payloads owed (published while subscribed with effective qos 1) and not yet acked, in publish order
        final List<String> owed = new ArrayList<>();
        // packet ids we hold unacked from the broker: id -> payload
        final Map<Integer, String> held = new LinkedHashMap<>();
        final Set<String> ackedPayloads = new HashSet<>();

        Client(String id) {
            this.id = id;
        }
    }

    @Test
    void randomRunsKeepInvariantsAndDeliverQos1ExactlyOnceAfterAckInOrder() {
        int totalQos1 = 0;
        int totalDup = 0;
        int totalResume = 0;
        for (int seed = 0; seed < 60; seed++) {
            Random rnd = new Random(seed);
            AtomicLong now = new AtomicLong(0);
            Broker b = new Broker(now::get, new Broker.Config(3, 1000, 50, 1_000_000));
            Map<String, Client> clients = new TreeMap<>();
            for (int i = 0; i < CLIENTS; i++) {
                clients.put("c" + i, new Client("c" + i));
            }
            // every client uses a persistent session and subscribes to "#"-style filters at qos 1
            int pubCounter = 0;
            for (int step = 0; step < 400; step++) {
                Client c = clients.get("c" + rnd.nextInt(CLIENTS));
                int op = rnd.nextInt(10);
                if (op == 0) {
                    if (!c.connected) {
                        boolean present = b.connect(c.id, false, null);
                        c.connected = true;
                        if (present) {
                            totalResume++;
                        }
                        // the client lost nothing it acked; unacked ones will be re-sent as dup
                    } else if (rnd.nextBoolean()) {
                        b.disconnect(c.id);
                        c.connected = false;
                    } else {
                        b.drop(c.id);
                        c.connected = false;
                    }
                } else if (op == 1 && c.connected) {
                    String f = randomFilter(rnd);
                    b.subscribe(c.id, f, 1);
                    c.subs.put(f, 1);
                } else if (op == 2 && c.connected && !c.subs.isEmpty()) {
                    String f = new ArrayList<>(c.subs.keySet()).get(rnd.nextInt(c.subs.size()));
                    b.unsubscribe(c.id, f);
                    c.subs.remove(f);
                } else if (op <= 5) {
                    String topic = randomTopic(rnd);
                    String payload = "p" + (pubCounter++);
                    b.publish(topic, payload, 1, false);
                    for (Client o : clients.values()) {
                        if (o.subs.keySet().stream().anyMatch(f -> Topics.matches(f, topic))) {
                            o.owed.add(payload);
                            totalQos1++;
                        }
                    }
                } else if (op <= 8) {
                    if (c.connected) {
                        for (Delivery dl : b.drain(c.id)) {
                            assertEquals(1, dl.qos());
                            if (dl.dup()) {
                                totalDup++;
                            }
                            String prev = c.held.put(dl.packetId(), dl.payload());
                            if (prev != null) {
                                assertEquals(prev, dl.payload(), "a packet id never changes payload while unacked");
                                assertTrue(dl.dup(), "re-sent copy must carry dup");
                            }
                        }
                        // ack a random subset of held
                        for (Iterator<Map.Entry<Integer, String>> it = c.held.entrySet().iterator(); it.hasNext();) {
                            Map.Entry<Integer, String> e = it.next();
                            if (rnd.nextInt(3) > 0) {
                                assertTrue(b.ack(c.id, e.getKey()));
                                assertTrue(c.ackedPayloads.add(e.getValue()), "acked twice: " + e.getValue());
                                assertEquals(e.getValue(), c.owed.stream().filter(e.getValue()::equals).findFirst().orElse(null));
                                it.remove();
                            }
                        }
                    }
                } else {
                    now.addAndGet(60);
                    b.tick();
                }
                b.checkInvariants();
            }
            // wind down: connect everyone, ack everything until quiescent
            for (Client c : clients.values()) {
                if (!c.connected) {
                    b.connect(c.id, false, null);
                    c.connected = true;
                }
            }
            for (int round = 0; round < 2000; round++) {
                boolean any = false;
                for (Client c : clients.values()) {
                    for (Delivery dl : b.drain(c.id)) {
                        any = true;
                        c.held.put(dl.packetId(), dl.payload());
                    }
                    for (Iterator<Map.Entry<Integer, String>> it = c.held.entrySet().iterator(); it.hasNext();) {
                        Map.Entry<Integer, String> e = it.next();
                        assertTrue(b.ack(c.id, e.getKey()));
                        assertTrue(c.ackedPayloads.add(e.getValue()), "acked twice: " + e.getValue());
                        it.remove();
                        any = true;
                    }
                }
                if (!any) {
                    break;
                }
            }
            for (Client c : clients.values()) {
                // every owed message acked exactly once (sets prove no duplicates; sizes prove nothing lost)
                assertEquals(
                        new HashSet<>(c.owed), c.ackedPayloads, "seed " + seed + " client " + c.id + " owed vs acked");
                assertEquals(c.owed.size(), c.ackedPayloads.size(), "owed list has no repeats");
                assertEquals(0, b.inflightCount(c.id));
                assertEquals(0, b.pendingCount(c.id));
            }
            b.checkInvariants();
        }
        assertTrue(totalQos1 > 3000, "generator volume: " + totalQos1);
        assertTrue(totalDup > 30, "redelivery exercised: " + totalDup);
        assertTrue(totalResume > 100, "resume exercised: " + totalResume);
    }

    @Test
    void deliveryOrderPerClientFollowsPublishOrder() {
        AtomicLong now = new AtomicLong(0);
        Broker b = new Broker(now::get, new Broker.Config(4, 1000, 50, 1_000_000));
        b.connect("s", false, null);
        b.subscribe("s", "#", 1);
        List<String> expected = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Set<String> seenSet = new HashSet<>();
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            if (rnd.nextInt(7) == 0) {
                b.drop("s");
                b.connect("s", false, null);
            }
            b.publish("x/" + (i % 3), "m" + i, 1, false);
            expected.add("m" + i);
            if (rnd.nextInt(3) == 0) {
                for (Delivery dl : b.drain("s")) {
                    if (seenSet.add(dl.payload())) {
                        seen.add(dl.payload());
                    }
                    b.ack("s", dl.packetId());
                }
            }
        }
        for (int k = 0; k < 200; k++) {
            for (Delivery dl : b.drain("s")) {
                if (seenSet.add(dl.payload())) {
                    seen.add(dl.payload());
                }
                b.ack("s", dl.packetId());
            }
        }
        assertEquals(expected, seen);
    }

    static String randomFilter(Random rnd) {
        return SubscriptionTrieTest.randomFilter(rnd, WORDS);
    }

    static String randomTopic(Random rnd) {
        return SubscriptionTrieTest.randomTopic(rnd, WORDS);
    }
}
