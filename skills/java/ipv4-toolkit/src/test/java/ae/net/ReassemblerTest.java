package ae.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReassemblerTest {
    static final int A = Ipv4PacketTest.A;
    static final int B = Ipv4PacketTest.B;

    /** Fragment of datagram {@code id}: offset in 8-byte units, {@code len} bytes all equal to {@code fill}. */
    static Ipv4Packet frag(int id, int offUnits, int len, boolean mf, int fill) {
        byte[] p = new byte[len];
        Arrays.fill(p, (byte) fill);
        return new Ipv4Packet(0, id, false, mf, offUnits, 64, 17, A, B, new byte[0], p);
    }

    static Ipv4Packet fragOf(int src, int proto, int id, int offUnits, int len, boolean mf, int fill) {
        byte[] p = new byte[len];
        Arrays.fill(p, (byte) fill);
        return new Ipv4Packet(0, id, false, mf, offUnits, 64, proto, src, B, new byte[0], p);
    }

    @Test
    void nonFragmentPassesThroughWithoutTouchingState() {
        Reassembler r = new Reassembler(1000, 4);
        Ipv4Packet p = frag(1, 0, 10, false, 7);
        Optional<Ipv4Packet> out = r.accept(p, 0);
        assertSame(p, out.orElseThrow());
        assertEquals(0, r.pending());
    }

    @Test
    void inOrderReassembly() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 0, 16, true, 1), 0).isEmpty());
        assertEquals(1, r.pending());
        assertTrue(r.accept(frag(1, 2, 16, true, 2), 1).isEmpty());
        Ipv4Packet out = r.accept(frag(1, 4, 5, false, 3), 2).orElseThrow();
        assertEquals(0, r.pending());
        assertEquals(37, out.payload().length);
        assertEquals(1, out.payload()[0]);
        assertEquals(1, out.payload()[15]);
        assertEquals(2, out.payload()[16]);
        assertEquals(2, out.payload()[31]);
        assertEquals(3, out.payload()[32]);
        assertEquals(3, out.payload()[36]);
        assertFalse(out.mf());
        assertEquals(0, out.fragOffset());
        assertEquals(1, out.id());
    }

    @Test
    void reverseOrderReassembly() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 4, 5, false, 3), 0).isEmpty());
        assertTrue(r.accept(frag(1, 2, 16, true, 2), 1).isEmpty());
        Ipv4Packet out = r.accept(frag(1, 0, 16, true, 1), 2).orElseThrow();
        assertEquals(37, out.payload().length);
        assertEquals(1, out.payload()[0]);
        assertEquals(3, out.payload()[36]);
    }

    @Test
    void holeInTheMiddleKeepsItWaiting() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 0, 8, true, 1), 0).isEmpty());
        assertTrue(r.accept(frag(1, 2, 8, false, 3), 1).isEmpty());
        assertEquals(1, r.pending());
        Ipv4Packet out = r.accept(frag(1, 1, 8, true, 2), 2).orElseThrow();
        assertArrayEquals(new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3},
                out.payload());
    }

    @Test
    void missingOffsetZeroFragmentNeverCompletes() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 1, 8, true, 2), 0).isEmpty());
        assertTrue(r.accept(frag(1, 2, 8, false, 3), 1).isEmpty());
        assertEquals(1, r.pending());
        assertTrue(r.accept(frag(1, 0, 8, true, 1), 2).isPresent());
    }

    @Test
    void resultHeaderFieldsComeFromTheOffsetZeroFragment() {
        Reassembler r = new Reassembler(1000, 4);
        Ipv4Packet last = new Ipv4Packet(5, 9, false, false, 1, 3, 17, A, B, new byte[0], new byte[8]);
        Ipv4Packet first = new Ipv4Packet(0x20, 9, false, true, 0, 61, 17, A, B, Ipv4PacketTest.hex("01010101"),
                new byte[8]);
        assertTrue(r.accept(last, 0).isEmpty());
        Ipv4Packet out = r.accept(first, 1).orElseThrow();
        assertEquals(0x20, out.tos());
        assertEquals(61, out.ttl());
        assertEquals("01010101", Ipv4PacketTest.toHex(out.options()));
        assertEquals(16, out.payload().length);
    }

    @Test
    void keysAreIndependentOnIdSrcAndProtocol() {
        Reassembler r = new Reassembler(1000, 8);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(2, 0, 8, true, 2), 0);
        r.accept(fragOf(A + 1, 17, 1, 0, 8, true, 3), 0);
        r.accept(fragOf(A, 6, 1, 0, 8, true, 4), 0);
        assertEquals(4, r.pending());
        // the final piece for (A,17,id 1) completes only that datagram
        Ipv4Packet out = r.accept(frag(1, 1, 8, false, 9), 1).orElseThrow();
        assertEquals(1, out.payload()[0]);
        assertEquals(9, out.payload()[15]);
        assertEquals(3, r.pending());
        assertTrue(r.accept(frag(2, 1, 8, false, 9), 1).isPresent());
    }

    @Test
    void overlapFirstArrivalWinsLiteral() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 16, true, 'A'), 0);
        r.accept(frag(1, 1, 16, true, 'B'), 1); // covers 8..24, only 16..24 is new
        Ipv4Packet out = r.accept(frag(1, 3, 4, false, 'C'), 2).orElseThrow();
        String s = new String(out.payload(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("A".repeat(16) + "B".repeat(8) + "CCCC", s);
    }

    @Test
    void duplicateFragmentsAreHarmless() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(1, 0, 8, true, 7), 1);
        r.accept(frag(1, 0, 8, true, 1), 2);
        Ipv4Packet out = r.accept(frag(1, 1, 3, false, 2), 3).orElseThrow();
        assertEquals(11, out.payload().length);
        assertEquals(1, out.payload()[0], "first arrival wins over the later duplicate with other data");
    }

    @Test
    void completedEntryIsRemovedAndLateDuplicateStartsFresh() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        assertTrue(r.accept(frag(1, 1, 8, false, 2), 1).isPresent());
        assertEquals(0, r.pending());
        assertTrue(r.accept(frag(1, 1, 8, false, 2), 2).isEmpty());
        assertEquals(1, r.pending());
    }

    // ---- malformed input discards the entire datagram ----

    @Test
    void nonLastFragmentWithBadLengthDiscardsTheEntry() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        assertTrue(r.accept(frag(1, 1, 9, true, 2), 1).isEmpty());
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
        // the previously stored fragment is gone: one more good piece is not enough
        assertTrue(r.accept(frag(1, 1, 8, false, 3), 2).isEmpty());
        assertEquals(1, r.pending());
    }

    @Test
    void controlLastFragmentMayHaveAnyLength() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        assertTrue(r.accept(frag(1, 1, 9, false, 2), 1).isPresent());
        assertEquals(0, r.stats().malformed());
    }

    @Test
    void emptyFragmentPayloadIsMalformed() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 4, 0, true, 0), 0).isEmpty());
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
    }

    @Test
    void datagramBeyondMaximumSizeIsMalformed() {
        Reassembler r = new Reassembler(1000, 4);
        assertTrue(r.accept(frag(1, 8189, 3, false, 1), 0).isEmpty()); // end 65515 is the largest allowed
        assertEquals(0, r.stats().malformed());
        assertEquals(1, r.pending());
        assertTrue(r.accept(frag(2, 8189, 4, false, 1), 0).isEmpty()); // end 65516
        assertEquals(1, r.stats().malformed());
        assertEquals(1, r.pending());
    }

    @Test
    void conflictingLastFragmentsDiscardTheEntry() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 1, 8, false, 2), 0); // ends at 16
        assertTrue(r.accept(frag(1, 1, 7, false, 2), 1).isEmpty()); // ends at 15
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
    }

    @Test
    void laterLastFragmentEndingBeyondTheFirstLastIsAConflict() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 1, 8, false, 2), 0); // total 16
        assertTrue(r.accept(frag(1, 2, 8, false, 2), 1).isEmpty()); // claims total 24
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
    }

    @Test
    void identicalLastFragmentRepeatedIsNotAConflict() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 1, 8, false, 2), 0);
        r.accept(frag(1, 1, 8, false, 2), 1);
        assertEquals(0, r.stats().malformed());
        assertTrue(r.accept(frag(1, 0, 8, true, 1), 2).isPresent());
    }

    @Test
    void fragmentBeyondKnownLastEndDiscardsTheEntry() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 1, 8, false, 2), 0); // total 16
        assertTrue(r.accept(frag(1, 2, 8, true, 3), 1).isEmpty());
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
    }

    @Test
    void lastFragmentEndingBeforeAlreadyStoredDataDiscardsTheEntry() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 3, 8, true, 3), 0); // bytes 24..32 stored
        assertTrue(r.accept(frag(1, 1, 8, false, 1), 1).isEmpty()); // claims total 16 (offset 0 would be a non-fragment)
        assertEquals(1, r.stats().malformed());
        assertEquals(0, r.pending());
    }

    // ---- time and capacity ----

    @Test
    void entryJustInsideTimeoutStillCompletes() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 100);
        assertTrue(r.accept(frag(1, 1, 8, false, 2), 1099).isPresent());
        assertEquals(0, r.stats().expired());
    }

    @Test
    void entryAtExactlyTimeoutIsExpiredFirst() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 100);
        assertTrue(r.accept(frag(1, 1, 8, false, 2), 1100).isEmpty());
        assertEquals(1, r.stats().expired());
        assertEquals(1, r.pending(), "the late fragment starts a new entry");
    }

    @Test
    void timeoutRunsFromFirstArrivalNotFromLatest() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(1, 1, 8, true, 2), 600);
        assertTrue(r.accept(frag(1, 2, 8, false, 3), 1200).isEmpty());
        assertEquals(1, r.stats().expired());
    }

    @Test
    void unrelatedTrafficTriggersExpiryOfOtherEntries() {
        Reassembler r = new Reassembler(1000, 4);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(2, 0, 8, true, 1), 500);
        r.accept(frag(3, 0, 8, true, 1), 1000);
        assertEquals(1, r.stats().expired());
        assertEquals(2, r.pending());
    }

    @Test
    void fullTableEvictsTheOldestEntry() {
        Reassembler r = new Reassembler(10_000, 2);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(2, 0, 8, true, 2), 1);
        r.accept(frag(3, 0, 8, true, 3), 2);
        assertEquals(1, r.stats().evicted());
        assertEquals(2, r.pending());
        assertTrue(r.accept(frag(2, 1, 8, false, 9), 3).isPresent(), "datagram 2 survived");
        assertTrue(r.accept(frag(1, 1, 8, false, 9), 4).isEmpty(), "datagram 1 was evicted, its first half is gone");
    }

    @Test
    void evictionTieOnArrivalTimeGoesToTheEarlierInsertion() {
        Reassembler r = new Reassembler(10_000, 2);
        r.accept(frag(1, 0, 8, true, 1), 5);
        r.accept(frag(2, 0, 8, true, 2), 5);
        r.accept(frag(3, 0, 8, true, 3), 5);
        assertTrue(r.accept(frag(2, 1, 8, false, 9), 6).isPresent());
        assertTrue(r.accept(frag(1, 1, 8, false, 9), 6).isEmpty());
    }

    @Test
    void existingKeyDoesNotEvictWhenTableIsFull() {
        Reassembler r = new Reassembler(10_000, 2);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(2, 0, 8, true, 2), 1);
        r.accept(frag(1, 1, 8, true, 1), 2);
        assertEquals(0, r.stats().evicted());
        assertEquals(2, r.pending());
    }

    @Test
    void expiryFreesRoomBeforeEvictionIsConsidered() {
        Reassembler r = new Reassembler(100, 2);
        r.accept(frag(1, 0, 8, true, 1), 0);
        r.accept(frag(2, 0, 8, true, 2), 50);
        r.accept(frag(3, 0, 8, true, 100), 100);
        assertEquals(1, r.stats().expired());
        assertEquals(0, r.stats().evicted());
        assertEquals(2, r.pending());
    }

    // ---- differential: byte-coverage oracle with first-writer-wins ----

    @Test
    void randomizedOverlapsMatchTheCoverageOracleAtEveryStep() {
        Random rnd = new Random(77);
        for (int round = 0; round < 400; round++) {
            int total = 9 + rnd.nextInt(112); // > 8 so a last fragment can sit at offset >= 1 (offset 0 + !mf is a non-fragment)
            int fullUnits = total / 8;
            int steps = 3 + rnd.nextInt(12);
            Reassembler r = new Reassembler(1_000_000, 4);
            byte[] expected = new byte[total];
            boolean[] have = new boolean[total];
            boolean lastSeen = false;
            boolean done = false;
            for (int s = 0; s < steps && !done; s++) {
                Ipv4Packet f;
                int off;
                int len;
                boolean last = rnd.nextInt(4) == 0;
                int fill = 1 + rnd.nextInt(250);
                if (last) {
                    off = 1 + rnd.nextInt((total + 7) / 8 - 1); // offset unit with 8 <= off*8 < total
                    len = total - off * 8;
                    f = frag(1, off, len, false, fill);
                    lastSeen = true;
                } else {
                    int u = rnd.nextInt(fullUnits);
                    off = u;
                    int maxUnits = fullUnits - u;
                    len = 8 * (1 + rnd.nextInt(maxUnits));
                    f = frag(1, off, len, true, fill);
                }
                for (int i = off * 8; i < off * 8 + len; i++) {
                    if (!have[i]) {
                        have[i] = true;
                        expected[i] = (byte) fill;
                    }
                }
                boolean covered = true;
                for (boolean h : have) {
                    covered &= h;
                }
                Optional<Ipv4Packet> out = r.accept(f, s);
                boolean oracleDone = lastSeen && covered;
                assertEquals(oracleDone, out.isPresent(), "round " + round + " step " + s + " total " + total);
                if (oracleDone) {
                    assertArrayEquals(expected, out.get().payload(), "round " + round);
                    done = true;
                }
            }
        }
    }
}
