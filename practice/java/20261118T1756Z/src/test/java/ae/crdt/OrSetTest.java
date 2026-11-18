package ae.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OrSetTest {
    @Test
    void addContainsRemove() {
        OrSet<String> s = OrSet.<String>empty().add("x", "a");
        assertTrue(s.contains("x"));
        assertEquals(Set.of("x"), s.elements());
        OrSet<String> r = s.remove("x");
        assertFalse(r.contains("x"));
        assertEquals(Set.of(), r.elements());
        assertEquals(Set.of("x"), s.elements(), "original untouched");
    }

    @Test
    void concurrentAddBeatsRemove() {
        OrSet<String> base = OrSet.<String>empty().add("x", "a");
        OrSet<String> remover = base.remove("x");
        OrSet<String> adder = base.add("x", "b");
        assertEquals(Set.of("x"), remover.merge(adder).elements());
        assertEquals(Set.of("x"), adder.merge(remover).elements());
    }

    @Test
    void removeOfUnobservedAddDoesNotKillIt() {
        OrSet<String> a = OrSet.<String>empty().add("x", "a");
        OrSet<String> b = OrSet.<String>empty().remove("x");
        assertTrue(a.merge(b).contains("x"));
        assertEquals(b, b.remove("x"));
    }

    @Test
    void reAddAfterRemoveWorksAndSurvivesMergeWithOldState() {
        OrSet<String> s = OrSet.<String>empty().add("x", "a");
        OrSet<String> removed = s.remove("x");
        OrSet<String> readded = removed.add("x", "a");
        assertTrue(readded.contains("x"));
        assertTrue(readded.merge(s).contains("x"));
        assertTrue(s.merge(readded).contains("x"));
        assertFalse(removed.merge(s).contains("x"), "old add stays dead after remove");
    }

    @Test
    void sameReplicaTagsNeverCollide() {
        OrSet<String> s = OrSet.<String>empty().add("x", "a").remove("x").add("y", "a");
        OrSet<String> t = s.add("x", "a");
        assertEquals(Set.of("x", "y"), t.elements());
    }

    @Test
    void duplicateConcurrentAddsNeedBothRemoved() {
        OrSet<String> a = OrSet.<String>empty().add("x", "a");
        OrSet<String> b = OrSet.<String>empty().add("x", "b");
        OrSet<String> both = a.merge(b);
        // a removes after seeing only its own add; b's add survives
        OrSet<String> aRemoved = a.remove("x");
        assertTrue(aRemoved.merge(b).contains("x"));
        assertFalse(both.remove("x").contains("x"));
    }
}
