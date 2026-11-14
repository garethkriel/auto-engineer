package pc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistentStackTest {
    @Test
    void emptyStackBasics() {
        PersistentStack<String> s = PersistentStack.empty();
        assertTrue(s.isEmpty());
        assertEquals(0, s.size());
        assertTrue(s.peek().isEmpty());
        assertThrows(IllegalStateException.class, s::pop);
        assertEquals("[]", s.toString());
    }

    @Test
    void pushPeekPopLeaveOldVersionsUntouched() {
        PersistentStack<Integer> a = PersistentStack.<Integer>empty().push(1);
        PersistentStack<Integer> b = a.push(2);
        assertEquals(1, a.size());
        assertEquals(2, b.size());
        assertEquals(2, b.peek().orElseThrow());
        assertEquals(1, a.peek().orElseThrow());
        assertSame(a, b.pop());
    }

    @Test
    void iteratesTopFirstAndStreams() {
        PersistentStack<Integer> s = PersistentStack.<Integer>empty().push(1).push(2).push(3);
        List<Integer> seen = new ArrayList<>();
        for (int x : s) seen.add(x);
        assertEquals(List.of(3, 2, 1), seen);
        assertEquals(List.of(3, 2, 1), s.stream().toList());
        assertEquals("[3, 2, 1]", s.toString());
    }

    @Test
    void equalsAndHashCodeBySequence() {
        PersistentStack<Integer> x = PersistentStack.<Integer>empty().push(1).push(2);
        PersistentStack<Integer> y = PersistentStack.<Integer>empty().push(1).push(2);
        PersistentStack<Integer> z = PersistentStack.<Integer>empty().push(2).push(1);
        assertEquals(x, y);
        assertEquals(x.hashCode(), y.hashCode());
        assertNotEquals(x, z);
        assertNotEquals(x, PersistentStack.<Integer>empty());
        assertEquals(PersistentStack.<Integer>empty(), PersistentStack.<Integer>empty());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> PersistentStack.<String>empty().push(null));
    }

    @Test
    void largeStackDoesNotOverflow() {
        PersistentStack<Integer> s = PersistentStack.empty();
        for (int i = 0; i < 100_000; i++) s = s.push(i);
        PersistentStack<Integer> t = PersistentStack.empty();
        for (int i = 0; i < 100_000; i++) t = t.push(i);
        assertEquals(100_000, s.size());
        assertEquals(s, t);
        assertEquals(s.hashCode(), t.hashCode());
        assertEquals(99_999, s.peek().orElseThrow());
    }

    @Test
    void iteratorExhaustionThrows() {
        var it = PersistentStack.<Integer>empty().push(1).iterator();
        assertEquals(1, it.next());
        assertFalse(it.hasNext());
        assertThrows(java.util.NoSuchElementException.class, it::next);
    }
}
