package ae.skip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkipListMapTest {

    private static SkipListMap<Integer, String> of(int... keys) {
        SkipListMap<Integer, String> m = new SkipListMap<>();
        for (int k : keys) {
            m.put(k, "v" + k);
        }
        m.checkInvariants();
        return m;
    }

    private static Integer key(Optional<SkipListMap.Entry<Integer, String>> e) {
        return e.map(SkipListMap.Entry::key).orElse(null);
    }

    @Test
    void emptyMapBehaviour() {
        SkipListMap<Integer, String> m = new SkipListMap<>();
        assertTrue(m.isEmpty());
        assertEquals(0, m.size());
        assertNull(m.get(1));
        assertNull(m.remove(1));
        assertTrue(m.first().isEmpty());
        assertTrue(m.last().isEmpty());
        assertTrue(m.floor(1).isEmpty());
        assertEquals(0, m.rank(5));
        assertThrows(IndexOutOfBoundsException.class, () -> m.select(0));
        assertFalse(m.iterator().hasNext());
        m.checkInvariants();
    }

    @Test
    void putReturnsPreviousValueAndReplaces() {
        SkipListMap<Integer, String> m = new SkipListMap<>();
        assertNull(m.put(3, "a"));
        assertEquals("a", m.put(3, "b"));
        assertEquals("b", m.get(3));
        assertEquals(1, m.size());
        m.checkInvariants();
    }

    @Test
    void removeReturnsValueAndShrinks() {
        SkipListMap<Integer, String> m = of(1, 2, 3);
        assertEquals("v2", m.remove(2));
        assertNull(m.remove(2));
        assertEquals(2, m.size());
        assertFalse(m.containsKey(2));
        assertEquals(List.of(1, 3), keys(m));
        m.checkInvariants();
    }

    @Test
    void navigationOnGappedKeys() {
        SkipListMap<Integer, String> m = of(10, 20, 30);
        assertEquals(10, key(m.first()));
        assertEquals(30, key(m.last()));
        assertEquals(20, key(m.floor(20)));
        assertEquals(20, key(m.floor(25)));
        assertNull(key(m.floor(5)));
        assertEquals(20, key(m.ceiling(20)));
        assertEquals(30, key(m.ceiling(21)));
        assertNull(key(m.ceiling(31)));
        assertEquals(10, key(m.lower(20)));
        assertNull(key(m.lower(10)));
        assertEquals(30, key(m.higher(20)));
        assertNull(key(m.higher(30)));
        assertEquals(10, key(m.higher(-100)));
    }

    @Test
    void rankCountsStrictlyLessEvenForAbsentKeys() {
        SkipListMap<Integer, String> m = of(10, 20, 30, 40);
        assertEquals(0, m.rank(10));
        assertEquals(1, m.rank(20));
        assertEquals(2, m.rank(25));
        assertEquals(4, m.rank(41));
        assertEquals(0, m.rank(-5));
    }

    @Test
    void selectReturnsIthSmallest() {
        SkipListMap<Integer, String> m = of(40, 10, 30, 20);
        assertEquals(new SkipListMap.Entry<>(10, "v10"), m.select(0));
        assertEquals(new SkipListMap.Entry<>(40, "v40"), m.select(3));
        assertThrows(IndexOutOfBoundsException.class, () -> m.select(4));
        assertThrows(IndexOutOfBoundsException.class, () -> m.select(-1));
    }

    @Test
    void rangeIsHalfOpenAndAscending() {
        SkipListMap<Integer, String> m = of(1, 2, 3, 4, 5);
        assertEquals(List.of(2, 3, 4), rangeKeys(m.range(2, 5)));
        assertEquals(List.of(), rangeKeys(m.range(3, 3)));
        assertEquals(List.of(), rangeKeys(m.range(4, 2)));
        assertEquals(List.of(1, 2, 3, 4, 5), rangeKeys(m.range(-9, 99)));
        assertEquals(3, m.countRange(2, 5));
        assertEquals(0, m.countRange(4, 2));
        assertEquals(5, m.countRange(-9, 99));
    }

    @Test
    void customComparatorReversesOrder() {
        SkipListMap<String, Integer> m = new SkipListMap<>(Comparator.reverseOrder(), 7);
        for (String s : List.of("b", "d", "a", "c")) {
            m.put(s, s.charAt(0) - 'a');
        }
        m.checkInvariants();
        assertEquals("d", m.first().orElseThrow().key());
        assertEquals("a", m.last().orElseThrow().key());
        assertEquals("c", m.floor("c").orElseThrow().key());
        assertEquals("b", m.higher("c").orElseThrow().key()); // "higher" in comparator order
        assertEquals(1, m.rank("c"));
    }

    @Test
    void nullKeyAndNullValueRejected() {
        SkipListMap<Integer, String> m = of(1);
        assertThrows(NullPointerException.class, () -> m.put(null, "x"));
        assertThrows(NullPointerException.class, () -> m.put(2, null));
        assertThrows(NullPointerException.class, () -> m.get(null));
        assertThrows(NullPointerException.class, () -> m.remove(null));
        assertThrows(NullPointerException.class, () -> m.rank(null));
        assertEquals(1, m.size());
        m.checkInvariants();
    }

    @Test
    void iteratorIsFailFast() {
        SkipListMap<Integer, String> m = of(1, 2, 3);
        Iterator<SkipListMap.Entry<Integer, String>> it = m.iterator();
        it.next();
        m.put(9, "x");
        assertThrows(ConcurrentModificationException.class, it::next);
    }

    @Test
    void replacingValueDoesNotInvalidateIterator() {
        SkipListMap<Integer, String> m = of(1, 2, 3);
        Iterator<SkipListMap.Entry<Integer, String>> it = m.iterator();
        it.next();
        m.put(2, "new"); // not a structural change
        assertEquals("new", it.next().value());
    }

    @Test
    void clearResetsEverything() {
        SkipListMap<Integer, String> m = of(1, 2, 3);
        m.clear();
        assertEquals(0, m.size());
        assertTrue(m.first().isEmpty());
        m.checkInvariants();
        m.put(5, "five");
        assertEquals(0, m.rank(5));
        m.checkInvariants();
    }

    @Test
    void sameSeedGivesSameShape() {
        SkipListMap<Integer, String> a = new SkipListMap<>(null, 42);
        SkipListMap<Integer, String> b = new SkipListMap<>(null, 42);
        for (int i = 0; i < 500; i++) {
            a.put(i, "x");
            b.put(i, "x");
        }
        assertEquals(a.height(), b.height());
        assertEquals(a.levelCounts(), b.levelCounts());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 99, 2024})
    void differentialAgainstTreeMap(long seed) {
        Random rnd = new Random(seed);
        SkipListMap<Integer, String> m = new SkipListMap<>(null, seed);
        TreeMap<Integer, String> ref = new TreeMap<>();
        for (int step = 0; step < 3000; step++) {
            int k = rnd.nextInt(200);
            switch (rnd.nextInt(4)) {
                case 0, 1 -> assertEquals(ref.put(k, "v" + step), m.put(k, "v" + step));
                case 2 -> assertEquals(ref.remove(k), m.remove(k));
                default -> assertEquals(ref.get(k), m.get(k));
            }
            m.checkInvariants();
            assertEquals(ref.size(), m.size());
            if (step % 25 == 0) {
                compareAll(ref, m, rnd);
            }
        }
        compareAll(ref, m, rnd);
    }

    private static void compareAll(TreeMap<Integer, String> ref, SkipListMap<Integer, String> m, Random rnd) {
        assertEquals(new ArrayList<>(ref.keySet()), keys(m));
        for (int k = -2; k < 203; k += 7) {
            assertEquals(ref.floorKey(k), key(m.floor(k)), "floor " + k);
            assertEquals(ref.ceilingKey(k), key(m.ceiling(k)), "ceiling " + k);
            assertEquals(ref.lowerKey(k), key(m.lower(k)), "lower " + k);
            assertEquals(ref.higherKey(k), key(m.higher(k)), "higher " + k);
            assertEquals(ref.headMap(k, false).size(), m.rank(k), "rank " + k);
        }
        int i = 0;
        for (Map.Entry<Integer, String> e : ref.entrySet()) {
            assertEquals(new SkipListMap.Entry<>(e.getKey(), e.getValue()), m.select(i++));
        }
        int a = rnd.nextInt(210) - 5;
        int b = rnd.nextInt(210) - 5;
        List<Integer> expected = a >= b ? List.of() : new ArrayList<>(ref.subMap(a, b).keySet());
        assertEquals(expected, rangeKeys(m.range(a, b)));
        assertEquals(expected.size(), m.countRange(a, b));
    }

    @Test
    void largeInsertKeepsLogarithmicHeightAndFastRanks() {
        SkipListMap<Integer, Integer> m = new SkipListMap<>(null, 5);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            m.put(i * 2, i);
        }
        m.checkInvariants();
        assertEquals(n, m.size());
        assertTrue(m.height() >= 10 && m.height() <= 32, "height " + m.height());
        assertEquals(50_000, m.rank(100_000));
        assertEquals(77_777 * 2, m.select(77_777).key());
        assertEquals(10_000, m.countRange(1000, 21_000));
        for (int i = 0; i < n; i += 2) {
            assertEquals(i, m.remove(i * 2));
        }
        m.checkInvariants();
        assertEquals(n / 2, m.size());
        assertEquals(2, m.first().orElseThrow().key());
    }

    @Test
    void removingEverythingShrinksHeightToOne() {
        SkipListMap<Integer, String> m = of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 1; i <= 10; i++) {
            m.remove(i);
            m.checkInvariants();
        }
        assertEquals(1, m.height());
        assertTrue(m.isEmpty());
    }

    private static List<Integer> keys(SkipListMap<Integer, String> m) {
        List<Integer> out = new ArrayList<>();
        for (SkipListMap.Entry<Integer, String> e : m) {
            out.add(e.key());
        }
        return out;
    }

    private static List<Integer> rangeKeys(List<SkipListMap.Entry<Integer, String>> es) {
        List<Integer> out = new ArrayList<>();
        for (SkipListMap.Entry<Integer, String> e : es) {
            out.add(e.key());
        }
        return out;
    }
}
