package ae.merkle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MerkleMapTest {

    /** Keys grouped by bucket path, found by brute force (65536 buckets, ~200k keys). */
    static final Map<String, List<String>> BY_PATH = new HashMap<>();

    static {
        for (int i = 0; i < 200_000; i++) {
            String k = "k" + i;
            BY_PATH.computeIfAbsent(Oracle.path(k), p -> new ArrayList<>()).add(k);
        }
    }

    static List<String> crowdedBucket(int minSize) {
        return BY_PATH.values().stream().filter(l -> l.size() >= minSize).findFirst().orElseThrow();
    }

    @Test
    void emptyLeafHashIsKnownSha256OfSingleZeroByte() {
        // SHA-256 of the single byte 0x00 (published constant).
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
                HexFormat.of().formatHex(Hashing.EMPTY[4]));
    }

    @Test
    void emptyRootMatchesOracle() {
        MerkleMap m = new MerkleMap();
        assertArrayEquals(Oracle.root(Map.of()), m.rootHash());
        assertArrayEquals(Hashing.EMPTY[0], m.rootHash());
        assertEquals(0, m.size());
    }

    @Test
    void singleEntryRootMatchesOracle() {
        MerkleMap m = new MerkleMap();
        assertEquals(Optional.empty(), m.put("alpha", "1"));
        assertArrayEquals(Oracle.root(Map.of("alpha", "1")), m.rootHash());
        assertEquals(Optional.of("1"), m.get("alpha"));
        assertEquals(Optional.empty(), m.get("beta"));
    }

    @Test
    void putReturnsPreviousValueAndOverwriteChangesRoot() {
        MerkleMap m = new MerkleMap();
        m.put("a", "1");
        byte[] before = m.rootHash();
        assertEquals(Optional.of("1"), m.put("a", "2"));
        assertNotEquals(HexFormat.of().formatHex(before), HexFormat.of().formatHex(m.rootHash()));
        assertEquals(1, m.size());
        m.put("a", "1");
        assertArrayEquals(before, m.rootHash());
    }

    @Test
    void removeRestoresEarlierRootAndPrunesToEmpty() {
        MerkleMap m = new MerkleMap();
        m.put("a", "1");
        byte[] one = m.rootHash();
        m.put("b", "2");
        assertTrue(m.remove("b"));
        assertArrayEquals(one, m.rootHash());
        assertFalse(m.remove("b"));
        assertTrue(m.remove("a"));
        assertArrayEquals(Hashing.EMPTY[0], m.rootHash());
        assertEquals(0, m.size());
        m.checkInvariants();
        assertEquals(1, m.nodeCount(), "only the root remains after removing everything");
    }

    @Test
    void rootIsIndependentOfInsertionOrder() {
        Random rnd = new Random(7);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            keys.add("key-" + i);
        }
        MerkleMap a = new MerkleMap();
        keys.forEach(k -> a.put(k, "v" + k));
        List<String> shuffled = new ArrayList<>(keys);
        java.util.Collections.shuffle(shuffled, rnd);
        MerkleMap b = new MerkleMap();
        shuffled.forEach(k -> b.put(k, "v" + k));
        assertArrayEquals(a.rootHash(), b.rootHash());
    }

    @Test
    void bucketWithSeveralCollidingKeysHashesInKeyOrder() {
        List<String> ks = crowdedBucket(3);
        Map<String, String> content = new TreeMap<>();
        MerkleMap m = new MerkleMap();
        for (String k : ks.subList(0, 3).reversed()) {
            m.put(k, "x" + k);
            content.put(k, "x" + k);
        }
        assertArrayEquals(Oracle.root(content), m.rootHash());
        assertEquals(3, m.bucketEntries(Oracle.path(ks.get(0))).size());
        m.checkInvariants();
        m.remove(ks.get(1));
        content.remove(ks.get(1));
        assertArrayEquals(Oracle.root(content), m.rootHash());
    }

    @Test
    void lengthPrefixesPreventKeyValueBoundaryAmbiguity() {
        byte[] x = Hashing.leafHash(new TreeMap<>(Map.of("a", "bc")));
        byte[] y = Hashing.leafHash(new TreeMap<>(Map.of("ab", "c")));
        assertNotEquals(HexFormat.of().formatHex(x), HexFormat.of().formatHex(y));
        byte[] two = Hashing.leafHash(new TreeMap<>(Map.of("a", "", "b", "")));
        byte[] one = Hashing.leafHash(new TreeMap<>(Map.of("a", "\u0000\u0000\u0000\u0001b")));
        assertNotEquals(HexFormat.of().formatHex(two), HexFormat.of().formatHex(one));
    }

    @Test
    void emptyAndUnicodeKeysAndValuesWork() {
        Map<String, String> content = new TreeMap<>();
        MerkleMap m = new MerkleMap();
        for (String k : new String[] {"", "é", "日本語", "😀", "a\u0000b"}) {
            m.put(k, k.isEmpty() ? "" : k + k);
            content.put(k, k.isEmpty() ? "" : k + k);
        }
        assertArrayEquals(Oracle.root(content), m.rootHash());
        assertEquals(Optional.of(""), m.get(""));
        m.checkInvariants();
    }

    @Test
    void randomRunsMatchTreeMapAndOracleAfterEveryStep() {
        for (long seed = 1; seed <= 6; seed++) {
            Random rnd = new Random(seed);
            MerkleMap m = new MerkleMap();
            TreeMap<String, String> model = new TreeMap<>();
            // A small key space forces overwrites, removals of present keys and bucket collisions.
            List<String> space = new ArrayList<>(crowdedBucket(3));
            for (int i = 0; i < 25; i++) {
                space.add("s" + seed + "-" + i);
            }
            for (int step = 0; step < 150; step++) {
                String k = space.get(rnd.nextInt(space.size()));
                if (rnd.nextInt(3) == 0) {
                    assertEquals(model.remove(k) != null, m.remove(k));
                } else {
                    String v = "v" + rnd.nextInt(5);
                    assertEquals(Optional.ofNullable(model.put(k, v)), m.put(k, v));
                }
                assertEquals(model.size(), m.size());
                assertEquals(model, m.entries());
                assertArrayEquals(Oracle.root(model), m.rootHash(), "seed " + seed + " step " + step);
                m.checkInvariants();
            }
        }
    }

    @Test
    void copyIsIndependent() {
        MerkleMap m = new MerkleMap();
        m.put("a", "1");
        MerkleMap c = m.copy();
        assertArrayEquals(m.rootHash(), c.rootHash());
        c.put("b", "2");
        m.remove("a");
        assertEquals(Map.of("a", "1", "b", "2"), c.entries());
        assertEquals(Map.of(), m.entries());
    }

    @Test
    void hashAtAndChildHashesAreConsistentWithRoot() {
        MerkleMap m = new MerkleMap();
        for (int i = 0; i < 40; i++) {
            m.put("n" + i, "v");
        }
        byte[][] kids = m.childHashes("");
        assertEquals(16, kids.length);
        assertArrayEquals(m.rootHash(), Hashing.innerHash(kids));
        for (int i = 0; i < 16; i++) {
            assertArrayEquals(kids[i], m.hashAt(Integer.toHexString(i)));
        }
        // A path no key maps to hashes as the empty subtree of its level.
        String unused = null;
        for (int i = 0; i < 65536 && unused == null; i++) {
            String p = String.format("%04x", i);
            if (m.entries().keySet().stream().noneMatch(k -> Oracle.path(k).equals(p))) {
                unused = p;
            }
        }
        assertArrayEquals(Hashing.EMPTY[4], m.hashAt(unused));
    }

    @Test
    void badPathsAreRejected() {
        MerkleMap m = new MerkleMap();
        assertThrows(IllegalArgumentException.class, () -> m.hashAt("g"));
        assertThrows(IllegalArgumentException.class, () -> m.hashAt("00000"));
        assertThrows(IllegalArgumentException.class, () -> m.childHashes("0000"));
        assertThrows(IllegalArgumentException.class, () -> m.bucketEntries("000"));
        assertThrows(NullPointerException.class, () -> m.put(null, "x"));
        assertThrows(NullPointerException.class, () -> m.put("x", null));
    }

    @Test
    void checkerDetectsCorruptedCachedHashAndStaleCount() {
        MerkleMap m = new MerkleMap();
        m.put("a", "1");
        m.put("b", "2");
        m.corruptCachedHashForTest();
        IllegalStateException e = assertThrows(IllegalStateException.class, m::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: stale hash"), e.getMessage());

        MerkleMap n = new MerkleMap();
        n.put("a", "1");
        n.corruptCountForTest();
        e = assertThrows(IllegalStateException.class, n::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: count"), e.getMessage());

        MerkleMap p = new MerkleMap();
        p.put("a", "1");
        p.leaveEmptyChildForTest();
        e = assertThrows(IllegalStateException.class, p::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: empty node"), e.getMessage());
    }

    @Test
    void checkerDetectsEachRuleSeparately() {
        MerkleMap a = new MerkleMap();
        a.put("a", "1");
        a.corruptInnerHashForTest();
        IllegalStateException e = assertThrows(IllegalStateException.class, a::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: stale hash at '"), e.getMessage());

        MerkleMap b = new MerkleMap();
        b.put("a", "1");
        b.corruptLeafCountForTest();
        e = assertThrows(IllegalStateException.class, b::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: count mismatch at " + Oracle.path("a")), e.getMessage());

        MerkleMap c = new MerkleMap();
        c.put("a", "1");
        c.plantForeignKeyForTest("not-a");
        e = assertThrows(IllegalStateException.class, c::checkInvariants);
        assertTrue(e.getMessage().startsWith("invariant: key not-a in wrong bucket"), e.getMessage());
    }
}
