package ae.rbt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RbTreeMapTest {

    /** Keys 10,20,30,40,50 mapped to "a".."e". */
    private static RbTreeMap<Integer, String> five() {
        RbTreeMap<Integer, String> m = new RbTreeMap<>();
        m.put(30, "c");
        m.put(10, "a");
        m.put(50, "e");
        m.put(20, "b");
        m.put(40, "d");
        return m;
    }

    @Test
    void putGetAndReplaceReturnPreviousValue() {
        RbTreeMap<Integer, String> m = new RbTreeMap<>();
        assertNull(m.put(1, "x"));
        assertEquals("x", m.put(1, "y"));
        assertEquals("y", m.get(1));
        assertEquals(1, m.size());
        assertNull(m.get(2));
        assertTrue(m.containsKey(1));
        assertFalse(m.containsKey(2));
    }

    @Test
    void removeReturnsPreviousValueAndShrinks() {
        RbTreeMap<Integer, String> m = five();
        assertEquals("c", m.remove(30));
        assertNull(m.remove(30));
        assertNull(m.remove(31));
        assertEquals(4, m.size());
        assertEquals(List.of(10, 20, 40, 50), m.keys());
        m.check();
    }

    @Test
    void emptyMapBehaviour() {
        RbTreeMap<Integer, String> m = new RbTreeMap<>();
        assertTrue(m.isEmpty());
        assertThrows(NoSuchElementException.class, m::firstKey);
        assertThrows(NoSuchElementException.class, m::lastKey);
        assertEquals(Optional.empty(), m.pollFirst());
        assertEquals(Optional.empty(), m.floorKey(1));
        assertEquals(0, m.rank(5));
        assertEquals(0, m.countRange(0, 100));
        assertEquals(List.of(), m.keys());
        assertNull(m.remove(1));
        m.check();
    }

    @Test
    void nullArgumentsRejected() {
        RbTreeMap<Integer, String> m = five();
        assertThrows(NullPointerException.class, () -> m.put(null, "x"));
        assertThrows(NullPointerException.class, () -> m.put(1, null));
        assertThrows(NullPointerException.class, () -> m.get(null));
        assertThrows(NullPointerException.class, () -> m.remove(null));
        assertEquals(5, m.size());
    }

    @Test
    void firstLastAndPollFirst() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(10, m.firstKey());
        assertEquals(50, m.lastKey());
        assertEquals(Optional.of(Map.entry(10, "a")), m.pollFirst());
        assertEquals(20, m.firstKey());
        assertEquals(4, m.size());
    }

    @Test
    void floorCeilingLowerHigherOnPresentAndAbsentKeys() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(Optional.of(30), m.floorKey(30));
        assertEquals(Optional.of(30), m.floorKey(35));
        assertEquals(Optional.empty(), m.floorKey(9));
        assertEquals(Optional.of(30), m.ceilingKey(30));
        assertEquals(Optional.of(40), m.ceilingKey(31));
        assertEquals(Optional.empty(), m.ceilingKey(51));
        assertEquals(Optional.of(20), m.lowerKey(30));
        assertEquals(Optional.of(50), m.lowerKey(1000));
        assertEquals(Optional.empty(), m.lowerKey(10));
        assertEquals(Optional.of(40), m.higherKey(30));
        assertEquals(Optional.of(10), m.higherKey(-5));
        assertEquals(Optional.empty(), m.higherKey(50));
    }

    @Test
    void rankCountsStrictlySmallerKeys() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(0, m.rank(10));
        assertEquals(0, m.rank(-1));
        assertEquals(2, m.rank(30));
        assertEquals(3, m.rank(31));
        assertEquals(4, m.rank(50));
        assertEquals(5, m.rank(51));
    }

    @Test
    void selectReturnsSortedIndex() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(10, m.select(0));
        assertEquals(30, m.select(2));
        assertEquals(50, m.select(4));
        assertThrows(IndexOutOfBoundsException.class, () -> m.select(5));
        assertThrows(IndexOutOfBoundsException.class, () -> m.select(-1));
    }

    @Test
    void countRangeIsInclusiveAtBothEnds() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(5, m.countRange(10, 50));
        assertEquals(3, m.countRange(20, 40));
        assertEquals(2, m.countRange(21, 40)); // 30, 40
        assertEquals(1, m.countRange(30, 30));
        assertEquals(0, m.countRange(31, 39));
        assertEquals(0, m.countRange(40, 20));
        assertEquals(5, m.countRange(-100, 100));
    }

    @Test
    void entriesInRangeAreAscendingAndInclusive() {
        RbTreeMap<Integer, String> m = five();
        assertEquals(List.of(Map.entry(20, "b"), Map.entry(30, "c"), Map.entry(40, "d")), m.entries(20, 40));
        assertEquals(List.of(Map.entry(30, "c")), m.entries(25, 35));
        assertEquals(List.of(), m.entries(41, 49));
        assertEquals(List.of(), m.entries(40, 20));
    }

    @Test
    void reverseComparatorReversesEverything() {
        RbTreeMap<Integer, String> m = new RbTreeMap<>(Comparator.reverseOrder());
        for (int i = 1; i <= 5; i++) {
            m.put(i, "v" + i);
        }
        assertEquals(List.of(5, 4, 3, 2, 1), m.keys());
        assertEquals(5, m.firstKey());
        assertEquals(Optional.of(4), m.higherKey(5)); // "higher" follows the comparator
        assertEquals(2, m.rank(3)); // 5 and 4 precede 3 in reversed order
        assertEquals(Optional.of(2), m.floorKey(2));
        assertEquals(Optional.of(1), m.floorKey(0)); // 0 sorts after 1 in reversed order, so 1 is the greatest key <= 0
        assertEquals(Optional.empty(), m.ceilingKey(0)); // nothing sorts at or after 0
    }

    @Test
    void comparatorEqualKeyReplacesValueButKeepsOriginalKey() {
        RbTreeMap<String, Integer> m = new RbTreeMap<>(String.CASE_INSENSITIVE_ORDER);
        assertNull(m.put("a", 1));
        assertEquals(1, m.put("A", 2));
        assertEquals(1, m.size());
        assertEquals(2, m.get("a"));
        assertEquals(2, m.get("A"));
        assertEquals("a", m.firstKey());
    }

    @Test
    void ascendingInsertsStayBalancedAndSorted() {
        RbTreeMap<Integer, Integer> m = new RbTreeMap<>();
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            m.put(i, -i);
        }
        m.check();
        assertEquals(n, m.size());
        // 2 * ceil(log2(100001)) = 2 * 17 = 34
        assertTrue(m.height() <= 34, "height " + m.height());
        assertEquals(54_321, m.select(54_321));
        assertEquals(54_321, m.rank(54_321));
        assertEquals(1_001, m.countRange(500, 1_500));
    }

    @Test
    void heightStaysLogarithmicAfterRemovingMostKeys() {
        RbTreeMap<Integer, Integer> m = new RbTreeMap<>();
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            m.put(i, i);
        }
        for (int i = 0; i < n; i++) {
            if (i % 8 != 0) {
                m.remove(i);
            }
        }
        m.check();
        assertEquals(5_000, m.size());
        // 2 * ceil(log2(5001)) = 2 * 13 = 26
        assertTrue(m.height() <= 26, "height " + m.height());
        assertEquals(8, m.select(1));
        assertEquals(List.of(0, 8, 16), m.keys().subList(0, 3));
    }
}
