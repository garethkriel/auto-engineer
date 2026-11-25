package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TranspositionTableTest {
    @Test
    void missOnEmptyTable() {
        TranspositionTable t = new TranspositionTable(8);
        assertNull(t.probe(42L));
    }

    @Test
    void storeThenProbe() {
        TranspositionTable t = new TranspositionTable(8);
        t.store(5L, 3, TranspositionTable.Bound.EXACT, 77, 2);
        TranspositionTable.Entry e = t.probe(5L);
        assertNotNull(e);
        assertEquals(3, e.depth());
        assertEquals(TranspositionTable.Bound.EXACT, e.bound());
        assertEquals(77, e.value());
        assertEquals(2, e.move());
    }

    @Test
    void sameSlotDifferentKeyDoesNotAlias() {
        TranspositionTable t = new TranspositionTable(8);
        t.store(5L, 3, TranspositionTable.Bound.LOWER, 10, 0);
        assertNull(t.probe(5L + 8), "slot is shared (5 and 13 mod 8) but the key differs");
        t.store(13L, 1, TranspositionTable.Bound.UPPER, -4, 1);
        assertNull(t.probe(5L), "different key always replaces the old entry");
        assertEquals(-4, t.probe(13L).value());
    }

    @Test
    void sameKeyShallowerDoesNotOverwriteDeeper() {
        TranspositionTable t = new TranspositionTable(8);
        t.store(9L, 5, TranspositionTable.Bound.EXACT, 1, 0);
        t.store(9L, 2, TranspositionTable.Bound.EXACT, 2, 1);
        assertEquals(1, t.probe(9L).value());
        t.store(9L, 5, TranspositionTable.Bound.EXACT, 3, 1);
        assertEquals(3, t.probe(9L).value(), "equal depth replaces");
        t.store(9L, 6, TranspositionTable.Bound.LOWER, 4, 1);
        assertEquals(4, t.probe(9L).value());
    }

    @Test
    void negativeKeysUseFloorMod() {
        TranspositionTable t = new TranspositionTable(8);
        t.store(-1L, 1, TranspositionTable.Bound.EXACT, 6, 0);
        t.store(Long.MIN_VALUE, 1, TranspositionTable.Bound.EXACT, 7, 0);
        // floorMod(-1, 8) = 7 and floorMod(MIN_VALUE, 8) = 0: two different, valid slots.
        assertEquals(6, t.probe(-1L).value());
        assertEquals(7, t.probe(Long.MIN_VALUE).value());
    }

    @Test
    void sizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TranspositionTable(0));
        assertThrows(IllegalArgumentException.class, () -> new TranspositionTable(-3));
    }

    @Test
    void clearForgetsEverything() {
        TranspositionTable t = new TranspositionTable(4);
        t.store(1L, 1, TranspositionTable.Bound.EXACT, 1, 0);
        t.clear();
        assertNull(t.probe(1L));
    }
}
