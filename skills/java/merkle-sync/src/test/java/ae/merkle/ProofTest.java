package ae.merkle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ProofTest {

    static MerkleMap sample() {
        MerkleMap m = new MerkleMap();
        List<String> crowd = MerkleMapTest.crowdedBucket(3);
        for (int i = 0; i < 3; i++) {
            m.put(crowd.get(i), "crowd" + i);
        }
        for (int i = 0; i < 60; i++) {
            m.put("p" + i, "val" + i);
        }
        return m;
    }

    @Test
    void inclusionProofVerifiesForEveryKey() {
        MerkleMap m = sample();
        byte[] root = m.rootHash();
        for (Map.Entry<String, String> e : m.entries().entrySet()) {
            Proof p = m.prove(e.getKey());
            assertTrue(MerkleMap.verify(root, e.getKey(), Optional.of(e.getValue()), p), e.getKey());
        }
    }

    @Test
    void nonInclusionProofVerifiesInEmptyAndNonEmptyBuckets() {
        MerkleMap m = sample();
        byte[] root = m.rootHash();
        // Non-empty bucket: a key sharing the bucket of a stored key but not stored itself.
        List<String> crowd = MerkleMapTest.crowdedBucket(4);
        String stored = crowd.get(0);
        MerkleMap n = new MerkleMap();
        n.put(stored, "s");
        String absentSameBucket = crowd.get(3);
        Proof p1 = n.prove(absentSameBucket);
        assertEquals(1, p1.bucket().size(), "proof carries the stored neighbour");
        assertTrue(MerkleMap.verify(n.rootHash(), absentSameBucket, Optional.empty(), p1));
        // Empty bucket.
        Proof p2 = m.prove("never-stored-key-xyz");
        assertTrue(p2.bucket().isEmpty() || !m.entries().containsKey("never-stored-key-xyz"));
        assertTrue(MerkleMap.verify(root, "never-stored-key-xyz", Optional.empty(), p2));
    }

    @Test
    void proofOnEmptyMapVerifies() {
        MerkleMap m = new MerkleMap();
        assertTrue(MerkleMap.verify(m.rootHash(), "x", Optional.empty(), m.prove("x")));
    }

    @Test
    void controlAndWrongClaimsFail() {
        MerkleMap m = sample();
        byte[] root = m.rootHash();
        Proof p = m.prove("p3");
        assertTrue(MerkleMap.verify(root, "p3", Optional.of("val3"), p));          // control
        assertFalse(MerkleMap.verify(root, "p3", Optional.of("val4"), p));         // wrong value
        assertFalse(MerkleMap.verify(root, "p3", Optional.empty(), p));            // claims absence of a present key
        assertFalse(MerkleMap.verify(root, "p4", Optional.of("val3"), p));         // wrong key, other path
        byte[] other = root.clone();
        other[0] ^= 1;
        assertFalse(MerkleMap.verify(other, "p3", Optional.of("val3"), p));        // wrong root
        Proof absent = m.prove("nope-1");
        assertFalse(MerkleMap.verify(root, "nope-1", Optional.of("x"), absent));   // claims presence of an absent key
    }

    @Test
    void flippingAnySiblingByteBreaksVerification() {
        MerkleMap m = sample();
        byte[] root = m.rootHash();
        Proof p = m.prove("p7");
        int flips = 0;
        for (int level = 0; level < p.siblings().size(); level++) {
            for (int s = 0; s < p.siblings().get(level).size(); s++) {
                for (int b = 0; b < 32; b++) {
                    List<List<byte[]>> sib = deepCopy(p.siblings());
                    sib.get(level).get(s)[b] ^= 0x40;
                    flips++;
                    assertFalse(MerkleMap.verify(root, "p7", Optional.of("val7"),
                            new Proof(p.bucket(), sib)), "level " + level + " sibling " + s + " byte " + b);
                }
            }
        }
        assertEquals(4 * 15 * 32, flips, "4 levels x 15 siblings x 32 bytes");
    }

    @Test
    void tamperedBucketEntriesFail() {
        List<String> crowd = MerkleMapTest.crowdedBucket(3);
        MerkleMap m = new MerkleMap();
        for (String k : crowd.subList(0, 3)) {
            m.put(k, "v");
        }
        byte[] root = m.rootHash();
        String k0 = crowd.get(0);
        Proof p = m.prove(k0);
        assertEquals(3, p.bucket().size());
        assertTrue(MerkleMap.verify(root, k0, Optional.of("v"), p));
        // dropped neighbour
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"),
                new Proof(p.bucket().subList(0, 2), p.siblings())));
        // changed neighbour value
        List<Proof.Entry> changed = new ArrayList<>(p.bucket());
        int other = changed.get(0).key().equals(k0) ? 1 : 0;
        changed.set(other, new Proof.Entry(changed.get(other).key(), "w"));
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(changed, p.siblings())));
        // reversed order (must be strictly ascending by key)
        List<Proof.Entry> rev = new ArrayList<>(p.bucket()).reversed();
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(rev, p.siblings())));
        // duplicated entry
        List<Proof.Entry> dup = new ArrayList<>(p.bucket());
        dup.add(dup.get(0));
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(dup, p.siblings())));
    }

    @Test
    void bucketEntryOutsideThePathIsRejectedEvenWithMatchingHashes() {
        // Build a root over a bucket that (illegally) holds a key of another path; honest verifiers must reject it.
        String k = "p1";
        String foreign = "p2";
        assertFalse(Oracle.path(k).equals(Oracle.path(foreign)));
        MerkleMap m = new MerkleMap();
        m.put(k, "a");
        Proof good = m.prove(k);
        // control: honest proof passes
        assertTrue(MerkleMap.verify(m.rootHash(), k, Optional.of("a"), good));
        // forge: re-seal the bucket hash chain with the foreign entry included
        List<Proof.Entry> bucket = List.of(new Proof.Entry(k, "a"), new Proof.Entry(foreign, "b"));
        byte[] forgedRoot = reseal(k, bucket, good.siblings());
        assertFalse(MerkleMap.verify(forgedRoot, k, Optional.of("a"), new Proof(bucket, good.siblings())));
    }

    @Test
    void malformedShapesAreRejectedNotThrown() {
        MerkleMap m = sample();
        byte[] root = m.rootHash();
        Proof p = m.prove("p3");
        assertFalse(MerkleMap.verify(root, "p3", Optional.of("val3"), new Proof(p.bucket(), p.siblings().subList(0, 3))));
        List<List<byte[]>> shortRow = deepCopy(p.siblings());
        shortRow.get(2).remove(0);
        assertFalse(MerkleMap.verify(root, "p3", Optional.of("val3"), new Proof(p.bucket(), shortRow)));
        List<List<byte[]>> badLen = deepCopy(p.siblings());
        badLen.get(1).set(0, new byte[31]);
        assertFalse(MerkleMap.verify(root, "p3", Optional.of("val3"), new Proof(p.bucket(), badLen)));
        assertFalse(MerkleMap.verify(new byte[5], "p3", Optional.of("val3"), p));
    }

    @Test
    void randomProofsAcrossRandomMapsVerifyAndMismatchesFail() {
        Random rnd = new Random(99);
        for (int round = 0; round < 8; round++) {
            MerkleMap m = new MerkleMap();
            for (int i = 0; i < 50 + rnd.nextInt(100); i++) {
                m.put("r" + round + "-" + rnd.nextInt(200), "v" + rnd.nextInt(10));
            }
            byte[] root = m.rootHash();
            for (int q = 0; q < 40; q++) {
                String k = "r" + round + "-" + rnd.nextInt(300);
                Optional<String> v = m.get(k);
                Proof p = m.prove(k);
                assertTrue(MerkleMap.verify(root, k, v, p));
                Optional<String> wrong = v.isPresent() ? Optional.empty() : Optional.of("z");
                assertFalse(MerkleMap.verify(root, k, wrong, p));
            }
        }
    }

    static List<List<byte[]>> deepCopy(List<List<byte[]>> in) {
        List<List<byte[]>> out = new ArrayList<>();
        for (List<byte[]> row : in) {
            List<byte[]> r = new ArrayList<>();
            for (byte[] b : row) {
                r.add(b.clone());
            }
            out.add(r);
        }
        return out;
    }

    /** Recompute a root from a bucket and sibling rows using the oracle's own hashing of a bucket. */
    static byte[] reseal(String key, List<Proof.Entry> bucket, List<List<byte[]>> siblings) {
        Map<String, String> m = new java.util.TreeMap<>();
        bucket.forEach(e -> m.put(e.key(), e.value()));
        byte[] h = Oracle.bucketHash(m);
        String path = Oracle.path(key);
        for (int level = 3; level >= 0; level--) {
            int digit = Character.digit(path.charAt(level), 16);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1);
            int si = 0;
            for (int i = 0; i < 16; i++) {
                out.writeBytes(i == digit ? h : siblings.get(level).get(si++));
            }
            h = Oracle.sha(out.toByteArray());
        }
        return h;
    }

    @Test
    void extraRowsExtraSiblingsAndAdjacentDuplicatesAreRejected() {
        List<String> crowd = MerkleMapTest.crowdedBucket(3);
        MerkleMap m = new MerkleMap();
        for (String k : crowd.subList(0, 3)) {
            m.put(k, "v");
        }
        byte[] root = m.rootHash();
        String k0 = crowd.get(0);
        Proof p = m.prove(k0);
        assertTrue(MerkleMap.verify(root, k0, Optional.of("v"), p)); // control
        // a fifth row of siblings is ignored by a lax verifier
        List<List<byte[]>> extraRow = deepCopy(p.siblings());
        extraRow.add(new ArrayList<>(extraRow.get(0)));
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(p.bucket(), extraRow)));
        // a sixteenth sibling in a row is ignored by a lax verifier
        List<List<byte[]>> extraSibling = deepCopy(p.siblings());
        extraSibling.get(3).add(new byte[32]);
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(p.bucket(), extraSibling)));
        // the same entry listed twice in a row: the map collapses it, so only the strict check can reject it
        List<Proof.Entry> dup = new ArrayList<>(p.bucket());
        dup.add(1, dup.get(0));
        assertFalse(MerkleMap.verify(root, k0, Optional.of("v"), new Proof(dup, p.siblings())));
    }

    @Test
    void nullSiblingIsRejectedNotThrown() {
        MerkleMap m = sample();
        Proof p = m.prove("p3");
        List<List<byte[]>> sib = deepCopy(p.siblings());
        sib.get(0).set(4, null);
        assertFalse(MerkleMap.verify(m.rootHash(), "p3", Optional.of("val3"), new Proof(p.bucket(), sib)));
    }
}
