package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GenericsTest {
    @Test
    void maxOfComparables() {
        assertEquals(Optional.of(9), Generics.max(List.of(3, 9, 4)));
        assertEquals(Optional.empty(), Generics.max(List.<Integer>of()));
        assertEquals(Optional.of("pear"), Generics.max(Set.of("pear", "apple")));
    }

    @Test
    void maxSupportsSubtypeOfComparableSupertype() {
        // java.sql.Timestamp-like case: Sub extends Base implements Comparable<Base>
        List<Sub> subs = List.of(new Sub(1), new Sub(5), new Sub(3));
        assertEquals(5, Generics.max(subs).orElseThrow().n);
    }

    static class Base implements Comparable<Base> {
        final int n;
        Base(int n) { this.n = n; }
        public int compareTo(Base o) { return Integer.compare(n, o.n); }
    }
    static class Sub extends Base { Sub(int n) { super(n); } }

    @Test
    void copyIntoWidensDestination() {
        List<Number> dst = new ArrayList<>(List.of(0.5));
        Generics.<Integer>copyInto(dst, List.of(1, 2));
        assertEquals(List.of(0.5, 1, 2), dst);
    }

    @Test
    void sumMixedNumbers() {
        assertEquals(6.5, Generics.sum(List.of(1, 2.5, 3L)), 1e-9);
        assertEquals(0.0, Generics.sum(List.of()), 0.0);
    }

    @Test
    void keyOfMaxPicksFirstOnTie() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 3);
        m.put("b", 7);
        m.put("c", 7);
        assertEquals(Optional.of("b"), Generics.keyOfMax(m));
        assertEquals(Optional.empty(), Generics.keyOfMax(new HashMap<String, Integer>()));
    }
}
