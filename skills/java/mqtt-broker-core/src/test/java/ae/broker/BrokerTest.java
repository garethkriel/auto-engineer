package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrokerTest {
    AtomicLong now;
    Broker b;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1000);
        // maxInflight 2, maxQueued 3, retry 100, expiry 1000
        b = new Broker(now::get, new Broker.Config(2, 3, 100, 1000));
    }

    private static Delivery d(String topic, String payload, int qos, boolean retain, boolean dup, int id) {
        return new Delivery(topic, payload, qos, retain, dup, id);
    }

    @Test
    void basicFanOutQos0() {
        b.connect("pub", true, null);
        b.connect("s1", true, null);
        b.connect("s2", true, null);
        b.subscribe("s1", "t/+", 0);
        b.subscribe("s2", "t/#", 0);
        b.publish("t/x", "hi", 0, false);
        assertEquals(List.of(d("t/x", "hi", 0, false, false, 0)), b.drain("s1"));
        assertEquals(List.of(d("t/x", "hi", 0, false, false, 0)), b.drain("s2"));
        assertEquals(List.of(), b.drain("pub"));
        assertEquals(List.of(), b.drain("s1"), "drain clears");
        b.checkInvariants();
    }

    @Test
    void qosIsMinOfPublishAndSubscription() {
        b.connect("s", true, null);
        b.subscribe("s", "a", 0);
        b.publish("a", "x", 1, false);
        assertEquals(List.of(d("a", "x", 0, false, false, 0)), b.drain("s"));
        b.subscribe("s", "a", 1); // replace
        b.publish("a", "y", 0, false);
        assertEquals(List.of(d("a", "y", 0, false, false, 0)), b.drain("s"));
        b.publish("a", "z", 1, false);
        assertEquals(List.of(d("a", "z", 1, false, false, 1)), b.drain("s"));
    }

    @Test
    void overlappingSubscriptionsGetOneCopyAtHighestQos() {
        b.connect("s", true, null);
        b.subscribe("s", "a/+", 0);
        b.subscribe("s", "a/#", 1);
        b.subscribe("s", "a/b", 0);
        b.publish("a/b", "m", 1, false);
        assertEquals(List.of(d("a/b", "m", 1, false, false, 1)), b.drain("s"));
    }

    @Test
    void unsubscribeStopsDelivery() {
        b.connect("s", true, null);
        b.subscribe("s", "a", 0);
        assertTrue(b.unsubscribe("s", "a"));
        assertFalse(b.unsubscribe("s", "a"));
        b.publish("a", "x", 0, false);
        assertEquals(List.of(), b.drain("s"));
    }

    @Test
    void retainedDeliveredOnSubscribeWithRetainFlag() {
        b.connect("pub", true, null);
        b.publish("r/1", "one", 1, true);
        b.publish("r/2", "two", 0, true);
        b.publish("q/1", "other", 0, true);
        b.connect("s", true, null);
        b.subscribe("s", "r/#", 1);
        assertEquals(List.of(d("r/1", "one", 1, true, false, 1), d("r/2", "two", 0, true, false, 0)), b.drain("s"));
        // resubscribing delivers them again
        b.subscribe("s", "r/#", 0);
        assertEquals(List.of(d("r/1", "one", 0, true, false, 0), d("r/2", "two", 0, true, false, 0)), b.drain("s"));
        // live forwarded copy has retain=false
        b.publish("r/1", "uno", 0, true);
        assertEquals(List.of(d("r/1", "uno", 0, false, false, 0)), b.drain("s"));
    }

    @Test
    void emptyRetainedPayloadClears() {
        b.publish("r", "x", 0, true);
        assertEquals(1, b.retainedCount());
        b.publish("r", "", 0, true);
        assertEquals(0, b.retainedCount());
        b.connect("s", true, null);
        b.subscribe("s", "#", 0);
        assertEquals(List.of(), b.drain("s"));
        b.publish("r", "x", 0, false);
        assertEquals(1, b.drain("s").size());
        assertEquals(0, b.retainedCount(), "non-retained publish does not store");
    }

    @Test
    void retainedReplacedByNewerValue() {
        b.publish("r", "old", 0, true);
        b.publish("r", "new", 0, true);
        assertEquals(1, b.retainedCount());
        b.connect("s", true, null);
        b.subscribe("s", "r", 0);
        assertEquals("new", b.drain("s").get(0).payload());
    }

    @Test
    void dollarTopicRetainedNotSentToWildcardSubscriber() {
        b.publish("$SYS/up", "1", 0, true);
        b.connect("s", true, null);
        b.subscribe("s", "#", 0);
        assertEquals(List.of(), b.drain("s"));
        b.subscribe("s", "$SYS/#", 0);
        assertEquals(1, b.drain("s").size());
    }

    @Test
    void qos1WindowAndAckFlow() {
        b.connect("s", true, null);
        b.subscribe("s", "t", 1);
        for (int i = 1; i <= 4; i++) {
            b.publish("t", "m" + i, 1, false);
        }
        assertEquals(List.of(d("t", "m1", 1, false, false, 1), d("t", "m2", 1, false, false, 2)), b.drain("s"));
        assertEquals(2, b.inflightCount("s"));
        assertEquals(2, b.pendingCount("s"));
        assertTrue(b.ack("s", 1));
        assertEquals(List.of(d("t", "m3", 1, false, false, 3)), b.drain("s"));
        assertFalse(b.ack("s", 1), "double ack");
        assertFalse(b.ack("s", 999));
        assertTrue(b.ack("s", 2));
        assertTrue(b.ack("s", 3));
        assertEquals(List.of(d("t", "m4", 1, false, false, 4)), b.drain("s"));
        assertTrue(b.ack("s", 4));
        assertEquals(0, b.inflightCount("s"));
        assertEquals(0, b.pendingCount("s"));
        b.checkInvariants();
    }

    @Test
    void pendingQueueDropsOldest() {
        b.connect("s", true, null);
        b.subscribe("s", "t", 1);
        for (int i = 1; i <= 7; i++) { // 2 inflight + capacity 3 => m1,m2 sent; m3..m7 queued, m3,m4 dropped
            b.publish("t", "m" + i, 1, false);
        }
        assertEquals(2, b.droppedCount());
        b.drain("s");
        b.ack("s", 1);
        b.ack("s", 2);
        List<Delivery> got = b.drain("s");
        assertEquals(List.of("m5", "m6"), got.stream().map(Delivery::payload).toList());
        b.ack("s", got.get(0).packetId());
        assertEquals("m7", b.drain("s").get(0).payload());
    }

    @Test
    void retryResendsWithDupAfterInterval() {
        b.connect("s", true, null);
        b.subscribe("s", "t", 1);
        b.publish("t", "m", 1, false);
        b.drain("s");
        now.addAndGet(99);
        b.tick();
        assertEquals(List.of(), b.drain("s"));
        now.addAndGet(1);
        b.tick();
        assertEquals(List.of(d("t", "m", 1, false, true, 1)), b.drain("s"));
        b.tick();
        assertEquals(List.of(), b.drain("s"), "timer restarts after a resend");
        now.addAndGet(100);
        b.tick();
        assertEquals(List.of(d("t", "m", 1, false, true, 1)), b.drain("s"));
        b.ack("s", 1);
        now.addAndGet(500);
        b.tick();
        assertEquals(List.of(), b.drain("s"));
    }

    @Test
    void packetIdsCycleAndSkipIdsInUse() {
        Broker big = new Broker(now::get, new Broker.Config(65535, 10, 100, 1000));
        big.connect("s", true, null);
        big.subscribe("s", "t", 1);
        big.publish("t", "first", 1, false); // id 1, stays unacked
        // consume the rest of the id space except we ack as we go
        for (int i = 2; i <= 65535; i++) {
            big.publish("t", "x", 1, false);
        }
        List<Delivery> all = big.drain("s");
        assertEquals(65535, all.size());
        assertEquals(65535, all.get(65534).packetId());
        for (int i = 1; i < all.size(); i++) {
            big.ack("s", all.get(i).packetId());
        }
        // id 1 is still in use, counter wrapped: next id must be 2, not 1
        big.publish("t", "again", 1, false);
        assertEquals(2, big.drain("s").get(0).packetId());
        big.checkInvariants();
    }

    @Test
    void persistentSessionKeepsSubscriptionAndQueuesQos1WhileOffline() {
        b.connect("s", false, null);
        b.subscribe("s", "t", 1);
        b.disconnect("s");
        b.publish("t", "q0", 0, false);
        b.publish("t", "q1", 1, false);
        assertTrue(b.connect("s", false, null), "session present");
        assertEquals(List.of(d("t", "q1", 1, false, false, 1)), b.drain("s"));
        b.checkInvariants();
    }

    @Test
    void resumeResendsInflightFirstWithDupThenPending() {
        b.connect("s", false, null);
        b.subscribe("s", "t", 1);
        b.publish("t", "m1", 1, false);
        b.publish("t", "m2", 1, false);
        b.publish("t", "m3", 1, false); // pending (window 2)
        b.drain("s");
        b.drop("s");
        b.publish("t", "m4", 1, false);
        assertEquals(List.of(), b.drain("s"), "offline sessions have no outbox");
        assertTrue(b.connect("s", false, null));
        assertEquals(
                List.of(d("t", "m1", 1, false, true, 1), d("t", "m2", 1, false, true, 2)),
                b.drain("s"),
                "window of 2 is full of resent messages; pending waits");
        b.ack("s", 1);
        b.ack("s", 2);
        assertEquals(List.of("m3", "m4"), b.drain("s").stream().map(Delivery::payload).toList());
    }

    @Test
    void cleanConnectDiscardsStoredSession() {
        b.connect("s", false, null);
        b.subscribe("s", "t", 1);
        b.disconnect("s");
        assertFalse(b.connect("s", true, null));
        b.publish("t", "x", 1, false);
        assertEquals(List.of(), b.drain("s"));
        assertEquals(0, b.subscriptionCount());
    }

    @Test
    void cleanSessionErasedOnDisconnect() {
        b.connect("s", true, null);
        b.subscribe("s", "t", 0);
        b.disconnect("s");
        assertFalse(b.hasSession("s"));
        assertEquals(0, b.subscriptionCount());
        assertFalse(b.connect("s", false, null));
    }

    @Test
    void willPublishedOnDropNotOnGracefulDisconnect() {
        b.connect("watch", true, null);
        b.subscribe("watch", "will/#", 0);
        b.connect("a", true, new Broker.Will("will/a", "gone", 0, false));
        b.connect("b", true, new Broker.Will("will/b", "gone", 0, false));
        b.disconnect("a");
        assertEquals(List.of(), b.drain("watch"));
        b.drop("b");
        assertEquals(List.of(d("will/b", "gone", 0, false, false, 0)), b.drain("watch"));
        b.drop("b"); // already gone: no second will
        assertEquals(List.of(), b.drain("watch"));
    }

    @Test
    void retainedWill() {
        b.connect("a", true, new Broker.Will("status/a", "offline", 0, true));
        b.drop("a");
        assertEquals(1, b.retainedCount());
        b.connect("s", true, null);
        b.subscribe("s", "status/+", 0);
        assertEquals(List.of(d("status/a", "offline", 0, true, false, 0)), b.drain("s"));
    }

    @Test
    void takeoverPublishesOldWillAndNewConnectionOwnsSession() {
        b.connect("watch", true, null);
        b.subscribe("watch", "w", 0);
        b.connect("a", false, new Broker.Will("w", "old-gone", 0, false));
        b.subscribe("a", "t", 1);
        assertTrue(b.connect("a", false, null));
        assertEquals(List.of("old-gone"), b.drain("watch").stream().map(Delivery::payload).toList());
        b.drop("a"); // new connection has no will
        assertEquals(List.of(), b.drain("watch"));
    }

    @Test
    void sessionExpiry() {
        b.connect("s", false, null);
        b.subscribe("s", "t", 1);
        b.disconnect("s");
        now.addAndGet(999);
        b.tick();
        assertTrue(b.hasSession("s"));
        now.addAndGet(1);
        b.tick();
        assertFalse(b.hasSession("s"));
        assertEquals(0, b.subscriptionCount());
        assertFalse(b.connect("s", false, null));
    }

    @Test
    void connectedSessionsDoNotExpire() {
        b.connect("s", false, null);
        now.addAndGet(100000);
        b.tick();
        assertTrue(b.hasSession("s"));
    }

    @Test
    void argumentValidation() {
        b.connect("s", true, null);
        assertThrows(IllegalArgumentException.class, () -> b.publish("a/+", "x", 0, false));
        assertThrows(IllegalArgumentException.class, () -> b.publish("a", "x", 2, false));
        assertThrows(IllegalArgumentException.class, () -> b.publish("a", "x", -1, false));
        assertThrows(IllegalArgumentException.class, () -> b.subscribe("s", "a#", 0));
        assertThrows(IllegalArgumentException.class, () -> b.subscribe("s", "a", 2));
        assertThrows(IllegalArgumentException.class, () -> b.connect("", true, null));
        assertThrows(IllegalStateException.class, () -> b.subscribe("nobody", "a", 0));
        b.disconnect("s");
        assertThrows(IllegalStateException.class, () -> b.subscribe("s", "a", 0));
        assertThrows(IllegalArgumentException.class, () -> new Broker.Config(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Broker.Config(70000, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new Broker.Will("a/#", "x", 0, false));
    }

    @Test
    void failedSubscribeChangesNothing() {
        b.connect("s", true, null);
        try {
            b.subscribe("s", "bad#", 0);
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(0, b.subscriptionCount());
    }

    @Test
    void ackOnUnknownClientReturnsFalse() {
        assertFalse(b.ack("ghost", 1));
        assertEquals(List.of(), b.drain("ghost"));
        assertFalse(b.unsubscribe("ghost", "a"));
    }
}
