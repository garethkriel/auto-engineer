package perf;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class IntIntMapTest {
    @Test
    void putGetRemoveBasics() {
        IntIntMap m = new IntIntMap();
        assertEquals(OptionalInt.empty(), m.put(5, 50));
        assertEquals(OptionalInt.of(50), m.put(5, 51));
        assertEquals(OptionalInt.of(51), m.get(5));
        assertEquals(OptionalInt.empty(), m.get(6));
        assertEquals(7, m.getOrDefault(6, 7));
        assertTrue(m.containsKey(5));
        assertEquals(1, m.size());
        assertEquals(OptionalInt.of(51), m.remove(5));
        assertEquals(OptionalInt.empty(), m.remove(5));
        assertEquals(0, m.size());
        assertFalse(m.containsKey(5));
    }

    @Test
    void zeroAndExtremeKeysAreOrdinaryKeys() {
        IntIntMap m = new IntIntMap();
        int[] ks = {0, Integer.MIN_VALUE, Integer.MAX_VALUE, -1};
        for (int k : ks) {
            m.put(k, k == 0 ? 0 : k / 2);
        }
        assertEquals(4, m.size());
        assertEquals(OptionalInt.of(0), m.get(0));
        assertEquals(OptionalInt.of(Integer.MIN_VALUE / 2), m.get(Integer.MIN_VALUE));
        assertArrayEquals(new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, m.sortedKeys());
    }

    @Test
    void addToAccumulatesAndOverflowLeavesMapUnchanged() {
        IntIntMap m = new IntIntMap();
        assertEquals(3, m.addTo(1, 3));
        assertEquals(5, m.addTo(1, 2));
        m.put(2, Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> m.addTo(2, 1));
        assertEquals(OptionalInt.of(Integer.MAX_VALUE), m.get(2));
        assertEquals(2, m.size());
    }

    @Test
    void growsAsPowerOfTwoKeepingLoadAtMostHalf() {
        IntIntMap m = new IntIntMap();
        for (int i = 0; i < 1000; i++) {
            m.put(i, -i);
            assertEquals(Integer.bitCount(m.capacity()), 1);
            assertTrue(m.size() * 2 <= m.capacity(), "load too high at " + i);
        }
        for (int i = 0; i < 1000; i++) {
            assertEquals(OptionalInt.of(-i), m.get(i));
        }
    }

    @Test
    void removeInsideCollidingClusterKeepsOtherKeysReachable() {
        // Keys that share the low bits of their hash are forced into one probe cluster
        // by searching for them using a probe map of a fixed capacity.
        List<Integer> colliding = new ArrayList<>();
        for (int k = 0; colliding.size() < 40 && k < 5_000_000; k++) {
            if (homeSlot(k, 64) == 5) {
                colliding.add(k);
            }
        }
        assertEquals(40, colliding.size(), "need enough colliding keys");
        for (int removeIdx : new int[] {0, 1, 17, 39}) {
            IntIntMap m = new IntIntMap();
            for (int k : colliding) {
                m.put(k, k + 1);
            }
            m.remove(colliding.get(removeIdx));
            for (int i = 0; i < colliding.size(); i++) {
                int k = colliding.get(i);
                assertEquals(i == removeIdx ? OptionalInt.empty() : OptionalInt.of(k + 1), m.get(k),
                        "key index " + i + " after removing " + removeIdx);
            }
        }
    }

    /** Mirror of the map's hash, used only to craft colliding keys for a table of {@code cap} slots. */
    private static int homeSlot(int key, int cap) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 16)) & (cap - 1);
    }

    @Test
    void clearEmptiesButMapStaysUsable() {
        IntIntMap m = new IntIntMap();
        for (int i = 0; i < 100; i++) {
            m.put(i, i);
        }
        m.clear();
        assertEquals(0, m.size());
        assertEquals(OptionalInt.empty(), m.get(3));
        m.put(3, 9);
        assertEquals(OptionalInt.of(9), m.get(3));
    }

    @Test
    void forEachVisitsEveryEntryOnce() {
        IntIntMap m = new IntIntMap();
        for (int i = 0; i < 50; i++) {
            m.put(i * 7, i);
        }
        Map<Integer, Integer> seen = new TreeMap<>();
        m.forEach((k, v) -> assertNull(seen.put(k, v)));
        assertEquals(50, seen.size());
        assertEquals(6, seen.get(42));
    }

    @Test
    void randomisedDifferentialAgainstHashMap() {
        for (int seed = 0; seed < 20; seed++) {
            Random rnd = new Random(seed);
            IntIntMap mine = new IntIntMap();
            Map<Integer, Integer> ref = new HashMap<>();
            int range = seed % 2 == 0 ? 50 : 5000; // small ranges force many collisions/removals
            for (int op = 0; op < 20_000; op++) {
                int k = rnd.nextInt(range) - range / 2;
                switch (rnd.nextInt(4)) {
                    case 0, 1 -> {
                        int v = rnd.nextInt();
                        Integer old = ref.put(k, v);
                        assertEquals(old == null ? OptionalInt.empty() : OptionalInt.of(old), mine.put(k, v));
                    }
                    case 2 -> {
                        Integer old = ref.remove(k);
                        assertEquals(old == null ? OptionalInt.empty() : OptionalInt.of(old), mine.remove(k));
                    }
                    default -> {
                        Integer old = ref.get(k);
                        assertEquals(old == null ? OptionalInt.empty() : OptionalInt.of(old), mine.get(k));
                    }
                }
                assertEquals(ref.size(), mine.size());
            }
            int[] expected = ref.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            assertArrayEquals(expected, mine.sortedKeys(), "seed " + seed);
        }
    }

    @Test
    void millionsOfOperationsStayFast() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            IntIntMap m = new IntIntMap();
            int n = 2_000_000;
            for (int i = 0; i < n; i++) {
                m.addTo(i % 500_000, 1);
            }
            assertEquals(500_000, m.size());
            assertEquals(4, m.getOrDefault(123_456, -1));
            for (int i = 0; i < 500_000; i += 2) {
                m.remove(i);
            }
            assertEquals(250_000, m.size());
            assertFalse(m.containsKey(2));
            assertTrue(m.containsKey(3));
        });
    }
}
