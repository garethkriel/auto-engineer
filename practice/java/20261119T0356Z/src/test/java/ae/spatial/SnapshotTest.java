package ae.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class SnapshotTest {
    private static SpatialIndex sample() {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("b", 1.5, -2);
        ix.insert("a", 0, 0);
        ix.insert("é", 1e9, 3);
        return ix;
    }

    private static byte[] seal(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(body.length + 4).put(body).putInt((int) crc.getValue()).array();
    }

    @Test
    void literalLayoutForTwoEntries() throws Exception {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("b", 2, 0);
        ix.insert("a", 1, 0);
        byte[] snap = ix.snapshot();
        // magic 4 + count 4 + 2 * (len 4 + 1 id byte + 16 coords) + crc 4
        assertEquals(4 + 4 + 2 * 21 + 4, snap.length);
        assertArrayEquals("SPX1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(snap, 0, 4));
        ByteBuffer bb = ByteBuffer.wrap(snap);
        bb.position(4);
        assertEquals(2, bb.getInt());
        assertEquals(1, bb.getInt());
        assertEquals('a', bb.get());
        assertEquals(1.0, bb.getDouble());
        assertEquals(0.0, bb.getDouble());
        assertEquals(1, bb.getInt());
        assertEquals('b', bb.get());
    }

    @Test
    void roundTripPreservesEverything() throws Exception {
        SpatialIndex back = SpatialIndex.restore(sample().snapshot());
        assertEquals(3, back.size());
        assertEquals(new Point(1e9, 3), back.get("é").orElseThrow());
        assertEquals(new Point(1.5, -2), back.get("b").orElseThrow());
        back.checkInvariants();
    }

    @Test
    void emptyIndexRoundTrips() throws Exception {
        byte[] snap = new SpatialIndex().snapshot();
        assertEquals(12, snap.length);
        assertEquals(0, SpatialIndex.restore(snap).size());
    }

    @Test
    void bytesIgnoreInsertionOrderAndTreeShape() {
        Random rnd = new Random(7);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            order.add(i);
        }
        SpatialIndex first = new SpatialIndex();
        for (int i : order) {
            first.insert("id" + i, i * 1.25, (i * 37) % 101);
        }
        java.util.Collections.shuffle(order, rnd);
        SpatialIndex second = new SpatialIndex();
        for (int i : order) {
            second.insert("id" + i, -1e6, -1e6);   // widen bounds first, different shape
            second.move("id" + i, i * 1.25, (i * 37) % 101);
        }
        assertArrayEquals(first.snapshot(), second.snapshot());
    }

    @Test
    void restoredIndexKeepsSnapshotBytes() throws Exception {
        byte[] snap = sample().snapshot();
        assertArrayEquals(snap, SpatialIndex.restore(snap).snapshot());
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] snap = sample().snapshot();
        for (int len = 0; len < snap.length; len++) {
            byte[] cut = Arrays.copyOf(snap, len);
            assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(cut), "len " + len);
        }
    }

    @Test
    void everyFlippedByteIsRejected() {
        byte[] snap = sample().snapshot();
        for (int i = 0; i < snap.length; i++) {
            byte[] bad = snap.clone();
            bad[i] ^= 0x40;
            int at = i;
            assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(bad), "byte " + at);
        }
    }

    @Test
    void trailingGarbageRejected() {
        byte[] snap = sample().snapshot();
        byte[] longer = Arrays.copyOf(snap, snap.length + 1);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(longer));
    }

    @Test
    void resealedStructuralCorruptionIsRejected() {
        // body builders with a valid checksum so only structure checks can catch these
        ByteBuffer dup = ByteBuffer.allocate(4 + 4 + 2 * 21);
        dup.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(2);
        for (int i = 0; i < 2; i++) {
            dup.putInt(1).put((byte) 'a').putDouble(1).putDouble(2);
        }
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(dup.array())), "duplicate ids");

        ByteBuffer nan = ByteBuffer.allocate(4 + 4 + 21);
        nan.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        nan.putInt(1).put((byte) 'a').putDouble(Double.NaN).putDouble(2);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(nan.array())), "NaN");

        ByteBuffer hugeCount = ByteBuffer.allocate(8);
        hugeCount.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(Integer.MAX_VALUE);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(hugeCount.array())), "huge count");

        ByteBuffer negCount = ByteBuffer.allocate(8);
        negCount.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(-1);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(negCount.array())), "negative count");

        ByteBuffer hugeLen = ByteBuffer.allocate(8 + 4);
        hugeLen.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(Integer.MAX_VALUE);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(hugeLen.array())), "huge id length");

        ByteBuffer emptyId = ByteBuffer.allocate(4 + 4 + 20);
        emptyId.put("SPX1".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(0).putDouble(1).putDouble(1);
        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(seal(emptyId.array())), "empty id");

        assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(null));
    }

    @Test
    void randomGarbageNeverThrowsOtherTypes() {
        Random rnd = new Random(99);
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[rnd.nextInt(60)];
            rnd.nextBytes(junk);
            assertThrows(CorruptSnapshotException.class, () -> SpatialIndex.restore(junk));
        }
    }
}
