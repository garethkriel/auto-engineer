package ae.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegisterTest {
    @Test
    void emptyHasNoValueAndIsMergeIdentity() {
        LwwRegister<String> e = LwwRegister.empty();
        LwwRegister<String> r = e.set("x", 1, "a");
        assertEquals(Optional.empty(), e.get());
        assertEquals(r, e.merge(r));
        assertEquals(r, r.merge(e));
    }

    @Test
    void laterTimeWinsRegardlessOfMergeOrder() {
        LwwRegister<String> a = LwwRegister.<String>empty().set("old", 5, "a");
        LwwRegister<String> b = LwwRegister.<String>empty().set("new", 9, "b");
        assertEquals(Optional.of("new"), a.merge(b).get());
        assertEquals(Optional.of("new"), b.merge(a).get());
    }

    @Test
    void sameTimeBrokenByReplicaId() {
        LwwRegister<String> a = LwwRegister.<String>empty().set("fromA", 5, "a");
        LwwRegister<String> b = LwwRegister.<String>empty().set("fromB", 5, "b");
        assertEquals(Optional.of("fromB"), a.merge(b).get());
        assertEquals(Optional.of("fromB"), b.merge(a).get());
    }

    @Test
    void fullStampTieBrokenByValueAndStaysCommutative() {
        LwwRegister<String> a = LwwRegister.<String>empty().set("apple", 5, "a");
        LwwRegister<String> b = LwwRegister.<String>empty().set("banana", 5, "a");
        assertEquals(a.merge(b), b.merge(a));
        assertEquals(Optional.of("banana"), a.merge(b).get());
    }

    @Test
    void staleWriteLosesLocally() {
        LwwRegister<Integer> r = LwwRegister.<Integer>empty().set(1, 10, "a").set(2, 3, "a");
        assertEquals(Optional.of(1), r.get());
    }

    @Test
    void lawsOverAllStampCombinations() {
        String[] reps = {"a", "b"};
        java.util.List<LwwRegister<Integer>> all = new java.util.ArrayList<>();
        all.add(LwwRegister.empty());
        for (long t = 1; t <= 2; t++) {
            for (String rp : reps) {
                for (int v = 0; v < 2; v++) {
                    all.add(LwwRegister.<Integer>empty().set(v, t, rp));
                }
            }
        }
        assertEquals(9, all.size());
        for (var x : all) {
            assertEquals(x, x.merge(x));
            for (var y : all) {
                assertEquals(x.merge(y), y.merge(x));
                for (var z : all) {
                    assertEquals(x.merge(y).merge(z), x.merge(y.merge(z)));
                }
            }
        }
        assertTrue(all.get(0).equals(LwwRegister.<Integer>empty()));
    }
}
