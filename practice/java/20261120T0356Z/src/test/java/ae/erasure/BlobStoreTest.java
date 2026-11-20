package ae.erasure;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStoreTest {
    @TempDir
    Path root;

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private Path shard(int node, String name) {
        return root.resolve("node-" + node).resolve(name + ".shard");
    }

    @Test
    void roundTripVariousLengths() throws IOException {
        BlobStore store = new BlobStore(root, 4, 2);
        int[] lens = {0, 1, 3, 4, 5, 7, 8, 100, 1001, 65536};
        for (int len : lens) {
            byte[] data = bytes(len, len);
            store.put("b" + len, data);
            assertArrayEquals(data, store.get("b" + len), "len " + len);
        }
    }

    @Test
    void writesOneShardPerNodeWithExpectedSize() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        store.put("x", bytes(10, 1)); // shardLen = ceil(10/3) = 4
        for (int i = 0; i < 5; i++) {
            assertEquals(BlobStore.HEADER_SIZE + 4, Files.size(shard(i, "x")), "node " + i);
        }
        assertEquals(38, BlobStore.HEADER_SIZE);
    }

    @Test
    void headerFieldsAreBigEndianAtDocumentedOffsets() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        store.put("x", bytes(10, 1));
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(shard(4, "x")));
        assertEquals('E', b.get(0));
        assertEquals('R', b.get(1));
        assertEquals('S', b.get(2));
        assertEquals('1', b.get(3));
        assertEquals(3, b.getShort(4));
        assertEquals(2, b.getShort(6));
        assertEquals(4, b.getShort(8));
        assertEquals(10L, b.getLong(10));
        assertEquals(4, b.getInt(26));
        assertEquals(b.getLong(18), ByteBuffer.wrap(Files.readAllBytes(shard(0, "x"))).getLong(18), "same putId on all shards");
    }

    @Test
    void survivesLossOfAnyMNodes() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        byte[] data = bytes(500, 7);
        store.put("blob", data);
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                Path pa = shard(a, "blob");
                Path pb = shard(b, "blob");
                byte[] sa = Files.readAllBytes(pa);
                byte[] sb = Files.readAllBytes(pb);
                Files.delete(pa);
                Files.delete(pb);
                assertArrayEquals(data, store.get("blob"), "lost " + a + "," + b);
                Files.write(pa, sa);
                Files.write(pb, sb);
            }
        }
    }

    @Test
    void tooManyLostIsUnrecoverableButUnknownNameIsNoSuchElement() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        store.put("blob", bytes(50, 2));
        Files.delete(shard(0, "blob"));
        Files.delete(shard(1, "blob"));
        Files.delete(shard(2, "blob"));
        assertThrows(UnrecoverableBlobException.class, () -> store.get("blob"));
        assertThrows(NoSuchElementException.class, () -> store.get("never"));
        assertThrows(NoSuchElementException.class, () -> store.repair("never"));
    }

    @Test
    void flippingAnyByteOfOneShardIsDetectedAndDataStillReadable() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        byte[] data = bytes(9, 3); // shardLen 5 -> file size 43
        store.put("s", data);
        Path victim = shard(1, "s");
        byte[] original = Files.readAllBytes(victim);
        assertEquals(43, original.length);
        for (int i = 0; i < original.length; i++) {
            byte[] bad = original.clone();
            bad[i] ^= 0x10;
            Files.write(victim, bad);
            assertEquals(ShardState.CORRUPT, store.status("s").get(1), "flip byte " + i);
            assertEquals(ShardState.OK, store.status("s").get(0));
            assertArrayEquals(data, store.get("s"), "flip byte " + i);
        }
    }

    @Test
    void truncationAtEveryLengthIsCorruptNeverACrash() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        byte[] data = bytes(9, 4);
        store.put("t", data);
        Path victim = shard(0, "t");
        byte[] original = Files.readAllBytes(victim);
        for (int len = 0; len < original.length; len++) {
            Files.write(victim, java.util.Arrays.copyOf(original, len));
            assertEquals(ShardState.CORRUPT, store.status("t").get(0), "len " + len);
            assertArrayEquals(data, store.get("t"), "len " + len);
        }
        Files.write(victim, original);
        assertEquals(ShardState.OK, store.status("t").get(0));
    }

    @Test
    void resealedHeaderWithInconsistentBlobLenIsCorrupt() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        byte[] data = bytes(9, 3);
        store.put("c", data);
        byte[] f = Files.readAllBytes(shard(0, "c"));
        ByteBuffer.wrap(f).putLong(10, 100L); // blobLen no longer matches payloadLen (5 != ceil(100/2))
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(f, 0, BlobStore.HEADER_SIZE - 4);
        ByteBuffer.wrap(f).putInt(BlobStore.HEADER_SIZE - 4, (int) crc.getValue()); // valid header CRC
        Files.write(shard(0, "c"), f);
        assertEquals(ShardState.CORRUPT, store.status("c").get(0));
        assertArrayEquals(data, store.get("c"));
    }

    @Test
    void trailingGarbageIsCorrupt() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        store.put("g", bytes(9, 4));
        Files.write(shard(2, "g"), concat(Files.readAllBytes(shard(2, "g")), new byte[] {0}));
        assertEquals(ShardState.CORRUPT, store.status("g").get(2));
    }

    @Test
    void shardCopiedToWrongNodeIsCorrupt() throws IOException {
        BlobStore store = new BlobStore(root, 2, 2);
        byte[] data = bytes(40, 5);
        store.put("w", data);
        Files.copy(shard(0, "w"), shard(1, "w"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(List.of(ShardState.OK, ShardState.CORRUPT, ShardState.OK, ShardState.OK), store.status("w"));
        assertArrayEquals(data, store.get("w"));
    }

    @Test
    void staleShardFromOlderPutIsCorruptNotMixedIn() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        byte[] v1 = bytes(30, 1);
        store.put("v", v1);
        byte[] staleShard = Files.readAllBytes(shard(4, "v"));
        byte[] v2 = bytes(45, 2); // different blobLen
        store.put("v", v2);
        Files.write(shard(4, "v"), staleShard);
        assertEquals(ShardState.CORRUPT, store.status("v").get(4));
        assertArrayEquals(v2, store.get("v"));
        assertEquals(List.of(4), store.repair("v"));
        assertEquals(List.of(ShardState.OK, ShardState.OK, ShardState.OK, ShardState.OK, ShardState.OK),
                store.status("v"));
        assertArrayEquals(v2, store.get("v"));
    }

    @Test
    void staleShardSameLengthDifferentContentIsRejectedByPutId() throws IOException {
        // Same blobLen, different bytes: only the per-put id in the header tells the stale shard apart.
        BlobStore store = new BlobStore(root, 3, 2);
        store.put("q", bytes(30, 1));
        byte[] stale = Files.readAllBytes(shard(0, "q"));
        byte[] v2 = bytes(30, 2);
        store.put("q", v2);
        Files.write(shard(0, "q"), stale);
        assertEquals(ShardState.CORRUPT, store.status("q").get(0));
        assertArrayEquals(v2, store.get("q"));
    }

    @Test
    void repairRebuildsMissingAndCorruptShardsBitExact() throws IOException {
        BlobStore store = new BlobStore(root, 4, 3);
        byte[] data = bytes(333, 8);
        store.put("r", data);
        byte[][] orig = new byte[7][];
        for (int i = 0; i < 7; i++) {
            orig[i] = Files.readAllBytes(shard(i, "r"));
        }
        Files.delete(shard(1, "r"));
        byte[] bad = orig[5].clone();
        bad[BlobStore.HEADER_SIZE] ^= (byte) 0xFF;
        Files.write(shard(5, "r"), bad);
        Files.delete(shard(6, "r"));
        assertEquals(List.of(1, 5, 6), store.repair("r"));
        for (int i = 0; i < 7; i++) {
            assertArrayEquals(orig[i], Files.readAllBytes(shard(i, "r")), "node " + i);
        }
        assertEquals(List.of(), store.repair("r"));
    }

    @Test
    void repairFailsWhenTooFewValidShardsAndLeavesFilesAlone() throws IOException {
        BlobStore store = new BlobStore(root, 3, 1);
        store.put("z", bytes(20, 9));
        Files.delete(shard(0, "z"));
        Files.delete(shard(1, "z"));
        assertThrows(UnrecoverableBlobException.class, () -> store.repair("z"));
        assertFalse(Files.exists(shard(0, "z")));
        assertTrue(Files.exists(shard(2, "z")));
    }

    @Test
    void overwriteReplacesAllShards() throws IOException {
        BlobStore store = new BlobStore(root, 2, 2);
        store.put("o", bytes(100, 1));
        byte[] second = bytes(7, 2);
        store.put("o", second);
        assertArrayEquals(second, store.get("o"));
        assertEquals(List.of(ShardState.OK, ShardState.OK, ShardState.OK, ShardState.OK), store.status("o"));
    }

    @Test
    void deleteRemovesEverythingAndReportsWhetherAnythingExisted() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        store.put("d", bytes(10, 1));
        assertTrue(store.delete("d"));
        assertFalse(store.delete("d"));
        assertThrows(NoSuchElementException.class, () -> store.get("d"));
        try (Stream<Path> s = Files.walk(root)) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".shard") || p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void emptyBlobRoundTripsAndSurvivesLoss() throws IOException {
        BlobStore store = new BlobStore(root, 3, 2);
        store.put("e", new byte[0]);
        Files.delete(shard(0, "e"));
        Files.delete(shard(3, "e"));
        assertArrayEquals(new byte[0], store.get("e"));
        assertEquals(List.of(0, 3), store.repair("e"));
    }

    @Test
    void nameValidation() {
        BlobStore store = new BlobStore(root, 2, 1);
        for (String bad : new String[] {"", ".hidden", "a/b", "..", "a b", "x\\y", "é"}) {
            assertThrows(IllegalArgumentException.class, () -> store.put(bad, new byte[1]), "name '" + bad + "'");
            assertThrows(IllegalArgumentException.class, () -> store.get(bad));
        }
        assertThrows(NullPointerException.class, () -> store.put("ok", null));
    }

    @Test
    void geometryMismatchBetweenStoresMakesShardsCorrupt() throws IOException {
        new BlobStore(root, 3, 2).put("m", bytes(30, 1));
        BlobStore other = new BlobStore(root, 4, 1); // also 5 nodes, different geometry
        assertEquals(List.of(ShardState.CORRUPT, ShardState.CORRUPT, ShardState.CORRUPT, ShardState.CORRUPT,
                ShardState.CORRUPT), other.status("m"));
        assertThrows(UnrecoverableBlobException.class, () -> other.get("m"));
    }

    @Test
    void putLeavesNoTempFiles() throws IOException {
        BlobStore store = new BlobStore(root, 2, 1);
        store.put("n", bytes(10, 1));
        try (Stream<Path> s = Files.walk(root)) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void randomisedFaultInjectionAgainstOracle() throws IOException {
        Random rnd = new Random(2024);
        BlobStore store = new BlobStore(root, 5, 3);
        java.util.Map<String, byte[]> oracle = new java.util.HashMap<>();
        for (int step = 0; step < 150; step++) {
            String name = "k" + rnd.nextInt(6);
            int op = rnd.nextInt(10);
            if (op < 3) {
                byte[] d = bytes(rnd.nextInt(200), rnd.nextLong());
                store.put(name, d);
                oracle.put(name, d);
            } else if (op < 7 && oracle.containsKey(name)) {
                // damage up to m shards (delete, flip, truncate), then get must still succeed
                int damage = rnd.nextInt(4);
                boolean[] hit = new boolean[8];
                for (int j = 0; j < damage; j++) {
                    int node = rnd.nextInt(8);
                    if (hit[node]) {
                        continue;
                    }
                    hit[node] = true;
                    // damage accumulates across steps unless repaired: never exceed m = 3 bad shards in total
                    long bad = store.status(name).stream().filter(st -> st != ShardState.OK).count();
                    if (bad >= 3) {
                        break;
                    }
                    Path p = shard(node, name);
                    if (!Files.exists(p)) {
                        continue;
                    }
                    switch (rnd.nextInt(3)) {
                        case 0 -> Files.delete(p);
                        case 1 -> {
                            byte[] b = Files.readAllBytes(p);
                            b[rnd.nextInt(b.length)] ^= (byte) (1 + rnd.nextInt(255));
                            Files.write(p, b);
                        }
                        default -> Files.write(p, java.util.Arrays.copyOf(Files.readAllBytes(p),
                                rnd.nextInt((int) Files.size(p))));
                    }
                }
                assertArrayEquals(oracle.get(name), store.get(name), "step " + step);
                if (rnd.nextBoolean()) {
                    store.repair(name);
                    assertEquals(List.of(ShardState.OK, ShardState.OK, ShardState.OK, ShardState.OK, ShardState.OK,
                            ShardState.OK, ShardState.OK, ShardState.OK), store.status(name), "step " + step);
                }
            } else if (op == 7) {
                assertEquals(oracle.remove(name) != null, store.delete(name), "step " + step);
            } else if (!oracle.containsKey(name)) {
                assertThrows(NoSuchElementException.class, () -> store.get(name));
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
