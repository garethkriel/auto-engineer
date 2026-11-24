package ae.netroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AddrSetTest {
    private static final long BASE = Ip4.parse("10.1.0.0");
    private static final int W = 4096; // window 10.1.0.0 - 10.1.15.255

    private static AddrSet fromBits(boolean[] bits) {
        AddrSet s = AddrSet.EMPTY;
        for (int i = 0; i < W; ) {
            if (!bits[i]) { i++; continue; }
            int j = i;
            while (j + 1 < W && bits[j + 1]) j++;
            s = s.union(AddrSet.of(BASE + i, BASE + j));
            i = j + 1;
        }
        return s;
    }

    private static boolean[] randomBits(Random r) {
        boolean[] b = new boolean[W];
        int runs = r.nextInt(12);
        for (int k = 0; k < runs; k++) {
            int lo = r.nextInt(W), len = 1 + r.nextInt(r.nextBoolean() ? 20 : 900);
            for (int i = lo; i < Math.min(W, lo + len); i++) b[i] = true;
        }
        return b;
    }

    private static void assertMatches(boolean[] bits, AddrSet s, String what) {
        long count = 0;
        for (int i = 0; i < W; i++) {
            if (bits[i]) count++;
            assertEquals(bits[i], s.contains(BASE + i), what + " at " + i);
        }
        assertEquals(count, s.intersect(AddrSet.of(BASE, BASE + W - 1)).size(), what + " size in window");
    }

    @Test
    void emptyAndBasics() {
        assertTrue(AddrSet.EMPTY.isEmpty());
        assertEquals(0, AddrSet.EMPTY.size());
        assertEquals(1L << 32, AddrSet.EMPTY.complement().size());
        assertEquals(AddrSet.EMPTY, AddrSet.EMPTY.complement().complement());
        assertThrows(IllegalArgumentException.class, () -> AddrSet.of(5, 4));
        assertThrows(IllegalArgumentException.class, () -> AddrSet.of(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> AddrSet.of(0, 1L << 32));
    }

    @Test
    void adjacentRangesMerge() {
        AddrSet s = AddrSet.of(10, 19).union(AddrSet.of(20, 29)).union(AddrSet.of(40, 41));
        assertEquals(List.of(new long[] {10, 29}, new long[] {40, 41}).size(), s.ranges().size());
        assertArrayEquals(new long[] {10, 29}, s.ranges().get(0));
        assertArrayEquals(new long[] {40, 41}, s.ranges().get(1));
        assertEquals(22, s.size());
    }

    @Test
    void setAlgebraMatchesBooleanOracle() {
        Random r = new Random(11);
        for (int t = 0; t < 150; t++) {
            boolean[] a = randomBits(r), b = randomBits(r);
            AddrSet sa = fromBits(a), sb = fromBits(b);
            boolean[] u = new boolean[W], n = new boolean[W], d = new boolean[W], c = new boolean[W];
            for (int i = 0; i < W; i++) {
                u[i] = a[i] || b[i];
                n[i] = a[i] && b[i];
                d[i] = a[i] && !b[i];
                c[i] = !a[i];
            }
            assertMatches(a, sa, "a");
            assertMatches(u, sa.union(sb), "union");
            assertMatches(n, sa.intersect(sb), "intersect");
            assertMatches(d, sa.subtract(sb), "subtract");
            assertMatches(c, sa.complement(), "complement");
            assertEquals((1L << 32) - sa.size(), sa.complement().size());
            assertEquals(sa, sa.union(sa));
            assertEquals(sa.union(sb), sb.union(sa));
            assertTrue(sa.subtract(sa).isEmpty());
        }
    }

    @Test
    void cidrsLiteralCases() {
        assertEquals(List.of("10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/31", "10.0.0.6/32"),
            names(AddrSet.of(Ip4.parse("10.0.0.1"), Ip4.parse("10.0.0.6"))));
        assertEquals(List.of("0.0.0.0/0"), names(AddrSet.of(0, 0xFFFFFFFFL)));
        assertEquals(List.of("10.0.0.0/8"), names(AddrSet.of(Cidr.parse("10.0.0.0/8"))));
        assertTrue(names(AddrSet.EMPTY).isEmpty());
        List<String> almostAll = names(AddrSet.of(0, 0xFFFFFFFEL));
        assertEquals(32, almostAll.size());
        assertEquals("0.0.0.0/1", almostAll.get(0));
        assertEquals("255.255.255.254/32", almostAll.get(31));
        assertEquals(List.of("255.255.255.255/32"), names(AddrSet.of(0xFFFFFFFFL, 0xFFFFFFFFL)));
        assertEquals(List.of("0.0.0.0/32"), names(AddrSet.of(0, 0)));
    }

    private static List<String> names(AddrSet s) {
        return s.cidrs().stream().map(Cidr::toString).toList();
    }

    /** Minimal CIDR count on the window, via recursion over the aligned binary tree (independent of AddrSet). */
    private static int minBlocks(boolean[] bits, int lo, int size) {
        boolean all = true, none = true;
        for (int i = lo; i < lo + size; i++) {
            if (bits[i]) none = false; else all = false;
        }
        if (none) return 0;
        if (all) return 1;
        return minBlocks(bits, lo, size / 2) + minBlocks(bits, lo + size / 2, size / 2);
    }

    @Test
    void cidrsAreExactDisjointAndMinimal() {
        Random r = new Random(23);
        for (int t = 0; t < 150; t++) {
            boolean[] bits = randomBits(r);
            AddrSet s = fromBits(bits);
            List<Cidr> cs = s.cidrs();
            assertEquals(minBlocks(bits, 0, W), cs.size(), "minimal count");
            AddrSet rebuilt = AddrSet.EMPTY;
            long total = 0;
            long prevEnd = -1;
            for (Cidr c : cs) {
                assertTrue(c.first() > prevEnd, "ascending and disjoint");
                prevEnd = c.last();
                total += c.size();
                rebuilt = rebuilt.union(AddrSet.of(c));
            }
            assertEquals(s, rebuilt);
            assertEquals(s.size(), total);
        }
    }

    @Test
    void cidrsOfComplementCoverTheRest() {
        AddrSet s = AddrSet.of(Cidr.parse("10.0.0.0/8")).union(AddrSet.of(Cidr.parse("192.168.0.0/16")));
        AddrSet rest = AddrSet.EMPTY;
        for (Cidr c : s.complement().cidrs()) rest = rest.union(AddrSet.of(c));
        assertEquals(s.complement(), rest);
        assertTrue(rest.intersect(s).isEmpty());
        List<Cidr> none = new ArrayList<>(s.complement().cidrs());
        assertFalse(none.isEmpty());
    }
}
