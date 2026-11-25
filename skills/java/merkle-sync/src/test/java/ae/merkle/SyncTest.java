package ae.merkle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SyncTest {

    /** A remote backed by a MerkleMap that counts requests and lets tests tamper with replies. */
    static class Counting implements Remote {
        final MerkleMap map;
        int requests;

        Counting(MerkleMap map) {
            this.map = map;
        }

        @Override
        public byte[] hashAt(String path) {
            requests++;
            return map.hashAt(path);
        }

        @Override
        public byte[][] childHashes(String path) {
            requests++;
            return map.childHashes(path);
        }

        @Override
        public List<Proof.Entry> bucket(String path) {
            requests++;
            List<Proof.Entry> out = new ArrayList<>();
            map.bucketEntries(path).forEach((k, v) -> out.add(new Proof.Entry(k, v)));
            return out;
        }
    }

    static MerkleMap base(int n) {
        MerkleMap m = new MerkleMap();
        for (int i = 0; i < n; i++) {
            m.put("base-" + i, "v" + i);
        }
        return m;
    }

    @Test
    void equalMapsCostExactlyOneRequest() {
        MerkleMap a = base(200);
        Counting r = new Counting(base(200));
        Sync.Diff d = Sync.diff(a, r);
        assertTrue(d.isEmpty());
        assertEquals(1, r.requests);
        assertEquals(1, d.requests());
    }

    @Test
    void emptyVersusEmptyIsOneRequest() {
        Counting r = new Counting(new MerkleMap());
        assertEquals(1, Sync.diff(new MerkleMap(), r).requests());
    }

    @Test
    void singleChangedKeyCostsOneRootOnePerLevelAndOneBucket() {
        MerkleMap a = base(200);
        MerkleMap b = base(200);
        b.put("base-17", "CHANGED");
        Counting r = new Counting(b);
        Sync.Diff d = Sync.diff(a, r);
        assertEquals(Map.of("base-17", "CHANGED"), d.changed());
        assertTrue(d.localOnly().isEmpty());
        assertTrue(d.remoteOnly().isEmpty());
        // 1 (root hash) + 4 childHashes (levels 0..3) + 1 bucket
        assertEquals(6, r.requests);
    }

    @Test
    void classifiesLocalOnlyRemoteOnlyAndChanged() {
        MerkleMap a = base(50);
        MerkleMap b = base(50);
        a.put("only-local", "L");
        b.put("only-remote", "R");
        b.put("base-3", "other");
        a.remove("base-4");
        Sync.Diff d = Sync.diff(a, new Counting(b));
        assertEquals(new TreeSet<>(Set.of("only-local")), d.localOnly());
        assertEquals(new TreeMap<>(Map.of("only-remote", "R", "base-4", "v4")), d.remoteOnly());
        assertEquals(new TreeMap<>(Map.of("base-3", "other")), d.changed());
    }

    @Test
    void emptyLocalPullsEverything() {
        MerkleMap a = new MerkleMap();
        MerkleMap b = base(120);
        Sync.Diff d = Sync.diff(a, new Counting(b));
        assertEquals(120, d.remoteOnly().size());
        Sync.pull(a, new Counting(b));
        assertArrayEquals(b.rootHash(), a.rootHash());
        assertEquals(b.entries(), a.entries());
    }

    @Test
    void diffMatchesSymmetricDifferenceOracleAndRequestsAreBounded() {
        for (long seed = 1; seed <= 12; seed++) {
            Random rnd = new Random(seed);
            MerkleMap a = base(150);
            MerkleMap b = base(150);
            int edits = rnd.nextInt(12);
            for (int i = 0; i < edits; i++) {
                MerkleMap target = rnd.nextBoolean() ? a : b;
                String k = "base-" + rnd.nextInt(170);
                switch (rnd.nextInt(3)) {
                    case 0 -> target.put(k, "x" + rnd.nextInt(4));
                    case 1 -> target.remove(k);
                    default -> target.put("extra-" + rnd.nextInt(20), "e");
                }
            }
            Map<String, String> la = a.entries();
            Map<String, String> lb = b.entries();
            Set<String> expectLocalOnly = new TreeSet<>();
            Map<String, String> expectRemoteOnly = new TreeMap<>();
            Map<String, String> expectChanged = new TreeMap<>();
            for (String k : la.keySet()) {
                if (!lb.containsKey(k)) {
                    expectLocalOnly.add(k);
                } else if (!lb.get(k).equals(la.get(k))) {
                    expectChanged.put(k, lb.get(k));
                }
            }
            for (String k : lb.keySet()) {
                if (!la.containsKey(k)) {
                    expectRemoteOnly.put(k, lb.get(k));
                }
            }
            Counting r = new Counting(b);
            Sync.Diff d = Sync.diff(a, r);
            assertEquals(expectLocalOnly, d.localOnly(), "seed " + seed);
            assertEquals(expectRemoteOnly, d.remoteOnly(), "seed " + seed);
            assertEquals(expectChanged, d.changed(), "seed " + seed);
            int k = expectLocalOnly.size() + expectRemoteOnly.size() + expectChanged.size();
            assertEquals(r.requests, d.requests());
            // Each differing key lies in one bucket and so keeps at most 4 inner nodes + 1 bucket + root request.
            assertTrue(d.requests() <= 1 + 5 * k, "seed " + seed + ": " + d.requests() + " > 1+5*" + k);
            if (k == 0) {
                assertEquals(1, d.requests());
            }
        }
    }

    @Test
    void pullMakesLocalAnExactMirrorAndIsIdempotent() {
        Random rnd = new Random(5);
        MerkleMap a = base(300);
        MerkleMap b = base(300);
        for (int i = 0; i < 40; i++) {
            a.put("a-" + rnd.nextInt(80), "x");
            b.put("b-" + rnd.nextInt(80), "y");
            a.remove("base-" + rnd.nextInt(300));
            b.put("base-" + rnd.nextInt(300), "z" + i);
        }
        Counting r = new Counting(b);
        Sync.pull(a, r);
        assertArrayEquals(b.rootHash(), a.rootHash());
        assertEquals(b.entries(), a.entries());
        a.checkInvariants();
        Counting r2 = new Counting(b);
        Sync.Diff again = Sync.pull(a, r2);
        assertTrue(again.isEmpty());
        assertEquals(1, r2.requests);
    }

    // ---- hostile remotes -------------------------------------------------

    static void assertRejectedAndUntouched(Remote hostile, String messagePart) {
        MerkleMap a = base(60);
        a.put("local-only", "L");
        byte[] before = a.rootHash();
        Map<String, String> content = a.entries();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Sync.pull(a, hostile));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertArrayEquals(before, a.rootHash());
        assertEquals(content, a.entries());
    }

    static MerkleMap remoteMap() {
        MerkleMap b = base(60);
        b.put("remote-only", "R");
        b.put("base-9", "different");
        return b;
    }

    /** Delegating remote whose replies are rewritten by the supplied hooks. */
    static class Tamper implements Remote {
        final Counting inner = new Counting(remoteMap());
        java.util.function.UnaryOperator<byte[]> hash = x -> x;
        java.util.function.BiFunction<String, byte[][], byte[][]> kids = (p, x) -> x;
        java.util.function.BiFunction<String, List<Proof.Entry>, List<Proof.Entry>> bucket = (p, x) -> x;

        @Override
        public byte[] hashAt(String path) {
            return hash.apply(inner.hashAt(path));
        }

        @Override
        public byte[][] childHashes(String path) {
            return kids.apply(path, inner.childHashes(path));
        }

        @Override
        public List<Proof.Entry> bucket(String path) {
            return bucket.apply(path, inner.bucket(path));
        }
    }

    @Test
    void honestTamperWrapperSyncsControl() {
        MerkleMap a = base(60);
        Tamper t = new Tamper();
        Sync.pull(a, t);
        assertEquals(remoteMap().entries(), a.entries());
    }

    @Test
    void remoteAdvertisingWrongRootIsRejected() {
        Tamper t = new Tamper();
        t.hash = h -> {
            byte[] c = h.clone();
            c[0] ^= 1;
            return c;
        };
        assertRejectedAndUntouched(t, "children do not hash");
    }

    @Test
    void remoteChildHashesNotMatchingParentAreRejected() {
        Tamper t = new Tamper();
        t.kids = (p, k) -> {
            byte[][] c = new byte[16][];
            for (int i = 0; i < 16; i++) {
                c[i] = k[i].clone();
            }
            c[5][3] ^= 4;
            return c;
        };
        assertRejectedAndUntouched(t, "children do not hash");
    }

    @Test
    void remoteWrongChildCountIsRejected() {
        Tamper t = new Tamper();
        t.kids = (p, k) -> java.util.Arrays.copyOf(k, 15);
        assertRejectedAndUntouched(t, "expected 16");
    }

    @Test
    void remoteNullOrShortHashIsRejected() {
        Tamper t = new Tamper();
        t.kids = (p, k) -> {
            byte[][] c = k.clone();
            c[2] = new byte[7];
            return c;
        };
        assertRejectedAndUntouched(t, "malformed");
    }

    @Test
    void remoteBucketContentNotMatchingAdvertisedHashIsRejected() {
        Tamper t = new Tamper();
        t.bucket = (p, b) -> {
            List<Proof.Entry> c = new ArrayList<>(b);
            c.set(0, new Proof.Entry(c.get(0).key(), c.get(0).value() + "!"));
            return c;
        };
        assertRejectedAndUntouched(t, "bucket");
    }

    @Test
    void remoteBucketWithExtraEntryIsRejected() {
        Tamper t = new Tamper();
        t.bucket = (p, b) -> {
            List<Proof.Entry> c = new ArrayList<>(b);
            c.add(new Proof.Entry("zzz-foreign-" + p, "x"));
            return c;
        };
        assertRejectedAndUntouched(t, "bucket");
    }

    @Test
    void remoteWithTooManyChildHashesIsRejectedAsStateError() {
        Tamper t = new Tamper();
        t.kids = (p, k) -> java.util.Arrays.copyOf(k, 17);
        assertRejectedAndUntouched(t, "expected 16");
    }

    /**
     * A remote whose tree is internally consistent (every hash is the honest hash of what it serves) but which
     * stores one key in a bucket that key does not belong to. Only the path check can reject it.
     */
    static final class MisfiledRemote implements Remote {
        final Map<String, Map<String, String>> byPath = new java.util.HashMap<>();

        MisfiledRemote(String foreignKey, String intoBucket) {
            byPath.computeIfAbsent(intoBucket, p -> new TreeMap<>()).put(foreignKey, "v");
        }

        @Override
        public byte[] hashAt(String path) {
            return Oracle.hash(path, byPath);
        }

        @Override
        public byte[][] childHashes(String path) {
            byte[][] out = new byte[16][];
            for (int i = 0; i < 16; i++) {
                out[i] = Oracle.hash(path + Character.forDigit(i, 16), byPath);
            }
            return out;
        }

        @Override
        public List<Proof.Entry> bucket(String path) {
            List<Proof.Entry> out = new ArrayList<>();
            byPath.getOrDefault(path, Map.of()).forEach((k, v) -> out.add(new Proof.Entry(k, v)));
            return out;
        }
    }

    @Test
    void honestlyHashedButMisfiledBucketIsRejected() {
        String key = "misfiled";
        String home = Oracle.path(key);
        String wrong = home.equals("0000") ? "0001" : "0000";
        // control: the same remote with the key in its home bucket syncs fine
        MerkleMap a = new MerkleMap();
        MisfiledRemote good = new MisfiledRemote(key, home);
        Sync.pull(a, good);
        assertEquals(Map.of(key, "v"), a.entries());
        // defect: only the bucket placement differs
        MerkleMap b = new MerkleMap();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Sync.pull(b, new MisfiledRemote(key, wrong)));
        assertTrue(e.getMessage().contains("bad bucket entry"), e.getMessage());
        assertTrue(b.entries().isEmpty());
    }
}
