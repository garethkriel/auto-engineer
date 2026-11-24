package ae.netroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RouteTableTest {
    private static RouteTable table(String... routes) {
        RouteTable t = new RouteTable();
        for (String r : routes) {
            String[] p = r.split("=");
            t.add(Cidr.parse(p[0]), p[1]);
        }
        return t;
    }

    private static String hop(RouteTable t, String addr) {
        return t.lookup(Ip4.parse(addr)).map(RouteTable.Route::nextHop).orElse("-");
    }

    private static List<String> dump(RouteTable t) {
        return t.routes().stream().map(r -> r.cidr() + "=" + r.nextHop()).toList();
    }

    @Test
    void longestPrefixWins() {
        RouteTable t = table("10.0.0.0/8=A", "10.1.0.0/16=B", "10.1.2.0/24=C", "0.0.0.0/0=D");
        assertEquals("C", hop(t, "10.1.2.3"));
        assertEquals("B", hop(t, "10.1.3.3"));
        assertEquals("A", hop(t, "10.2.0.1"));
        assertEquals("D", hop(t, "11.0.0.1"));
        assertEquals("D", hop(t, "255.255.255.255"));
        assertEquals("D", hop(t, "0.0.0.0"));
        assertEquals(Cidr.parse("10.1.2.0/24"), t.lookup(Ip4.parse("10.1.2.3")).orElseThrow().cidr());
    }

    @Test
    void noRouteIsEmpty() {
        RouteTable t = table("10.0.0.0/8=A");
        assertEquals(Optional.empty(), t.lookup(Ip4.parse("9.255.255.255")));
        assertEquals(Optional.empty(), t.lookup(Ip4.parse("11.0.0.0")));
        assertEquals(Optional.empty(), new RouteTable().lookup(0));
    }

    @Test
    void hostRouteAndDefaultRoute() {
        RouteTable t = table("1.2.3.4/32=H", "0.0.0.0/0=D");
        assertEquals("H", hop(t, "1.2.3.4"));
        assertEquals("D", hop(t, "1.2.3.5"));
        assertEquals("D", hop(t, "1.2.3.3"));
    }

    @Test
    void addReplacesAndReportsPrevious() {
        RouteTable t = new RouteTable();
        assertNull(t.add(Cidr.parse("10.0.0.0/8"), "A"));
        assertEquals("A", t.add(Cidr.parse("10.0.0.0/8"), "B"));
        assertEquals(1, t.size());
        assertEquals("B", hop(t, "10.0.0.1"));
        assertThrows(NullPointerException.class, () -> t.add(Cidr.parse("10.0.0.0/8"), null));
    }

    @Test
    void removeKeepsOtherRoutesAndPrunes() {
        RouteTable t = table("10.0.0.0/8=A", "10.1.2.0/24=C");
        assertEquals(1 + 8 + 16, t.nodeCount()); // root + 8 + 16 more nodes on the 10.1.2/24 path
        assertFalse(t.remove(Cidr.parse("10.1.0.0/16"))); // on the path but no route
        assertFalse(t.remove(Cidr.parse("11.0.0.0/8")));
        assertTrue(t.remove(Cidr.parse("10.1.2.0/24")));
        assertEquals(1 + 8, t.nodeCount());
        assertEquals("A", hop(t, "10.1.2.3"));
        assertTrue(t.remove(Cidr.parse("10.0.0.0/8")));
        assertEquals(1, t.nodeCount());
        assertEquals(0, t.size());
    }

    @Test
    void removeParentKeepsChildren() {
        RouteTable t = table("10.0.0.0/8=A", "10.1.0.0/16=B");
        assertTrue(t.remove(Cidr.parse("10.0.0.0/8")));
        assertEquals("B", hop(t, "10.1.0.1"));
        assertEquals("-", hop(t, "10.2.0.1"));
        assertEquals(1 + 16, t.nodeCount());
    }

    @Test
    void routesAreOrderedByBaseThenLength() {
        RouteTable t = table("10.1.0.0/16=B", "9.0.0.0/8=Z", "10.0.0.0/8=A", "10.0.0.0/24=C", "200.0.0.0/4=X".replace("200", "192"));
        assertEquals(List.of("9.0.0.0/8=Z", "10.0.0.0/8=A", "10.0.0.0/24=C", "10.1.0.0/16=B", "192.0.0.0/4=X"), dump(t));
    }

    @Test
    void lookupMatchesLinearScanOracle() {
        Random r = new Random(5);
        for (int t = 0; t < 60; t++) {
            List<Cidr> cs = new ArrayList<>();
            List<String> hs = new ArrayList<>();
            RouteTable table = new RouteTable();
            for (int i = 0, n = 1 + r.nextInt(25); i < n; i++) {
                Cidr c = randomCidr(r);
                String h = "h" + r.nextInt(4);
                int ix = cs.indexOf(c);
                if (ix >= 0) hs.set(ix, h); else { cs.add(c); hs.add(h); }
                table.add(c, h);
            }
            if (!cs.isEmpty() && r.nextBoolean()) {
                int k = r.nextInt(cs.size());
                assertTrue(table.remove(cs.get(k)));
                cs.remove(k);
                hs.remove(k);
            }
            assertEquals(cs.size(), table.size());
            for (int q = 0; q < 300; q++) {
                long a = q < 150 ? BASE + r.nextInt(W) : r.nextLong() & 0xFFFFFFFFL;
                assertEquals(naive(cs, hs, a), table.lookup(a).map(RouteTable.Route::nextHop).orElse("-"),
                    Ip4.format(a));
            }
        }
    }

    static final long BASE = Ip4.parse("10.1.0.0");
    static final int W = 4096;

    /** Random CIDR mostly inside the 10.1.0.0/20 window so routes overlap often. */
    static Cidr randomCidr(Random r) {
        if (r.nextInt(8) == 0) return Cidr.containing(r.nextLong() & 0xFFFFFFFFL, r.nextInt(33));
        int len = 20 + r.nextInt(13);
        return Cidr.containing(BASE + r.nextInt(W), len);
    }

    private static String naive(List<Cidr> cs, List<String> hs, long a) {
        int best = -1;
        String hop = "-";
        for (int i = 0; i < cs.size(); i++) {
            if (cs.get(i).contains(a) && cs.get(i).len() > best) {
                best = cs.get(i).len();
                hop = hs.get(i);
            }
        }
        return hop;
    }

    // ---- aggregation -------------------------------------------------------------------------------------

    @Test
    void aggregateDropsRouteWithSameHopAsCover() {
        RouteTable t = table("10.0.0.0/8=A", "10.1.0.0/16=A", "10.2.0.0/16=B");
        assertEquals(List.of("10.0.0.0/8=A", "10.2.0.0/16=B"), dump(t.aggregate()));
    }

    @Test
    void aggregateKeepsRouteWithDifferentHopThanCover() {
        RouteTable t = table("10.0.0.0/8=A", "10.1.0.0/16=B");
        assertEquals(List.of("10.0.0.0/8=A", "10.1.0.0/16=B"), dump(t.aggregate()));
    }

    @Test
    void aggregateMergesEqualSiblings() {
        RouteTable t = table("10.0.0.0/25=A", "10.0.0.128/25=A");
        assertEquals(List.of("10.0.0.0/24=A"), dump(t.aggregate()));
    }

    @Test
    void aggregateDoesNotMergeSiblingsWithDifferentHops() {
        RouteTable t = table("10.0.0.0/25=A", "10.0.0.128/25=B");
        assertEquals(List.of("10.0.0.0/25=A", "10.0.0.128/25=B"), dump(t.aggregate()));
    }

    @Test
    void aggregateDoesNotMergeWhenSiblingMissing() {
        RouteTable t = table("10.0.0.0/25=A");
        assertEquals(List.of("10.0.0.0/25=A"), dump(t.aggregate()));
        RouteTable u = table("10.0.0.0/25=A", "10.0.0.128/26=A"); // second half only partly covered
        assertEquals(List.of("10.0.0.0/25=A", "10.0.0.128/26=A"), dump(u.aggregate()));
    }

    @Test
    void aggregateCascades() {
        RouteTable t = table("10.0.0.0/26=A", "10.0.0.64/26=A", "10.0.0.128/26=A", "10.0.0.192/26=A");
        assertEquals(List.of("10.0.0.0/24=A"), dump(t.aggregate()));
    }

    @Test
    void aggregateMergeOverridesParentRouteThatWasFullyShadowed() {
        // the /24 route is never used (both halves are more specific); merging replaces it by hop A
        RouteTable t = table("10.0.0.0/24=Z", "10.0.0.0/25=A", "10.0.0.128/25=A");
        RouteTable agg = t.aggregate();
        assertEquals(List.of("10.0.0.0/24=A"), dump(agg));
        assertTrue(t.equivalentTo(agg));
    }

    @Test
    void aggregateKeepsMoreSpecificRoutesUnderAMergedPair() {
        RouteTable t = table("10.0.0.0/25=A", "10.0.0.128/25=A", "10.0.0.64/26=B");
        RouteTable agg = t.aggregate();
        assertEquals(List.of("10.0.0.0/24=A", "10.0.0.64/26=B"), dump(agg));
        assertTrue(t.equivalentTo(agg));
    }

    @Test
    void aggregateAfterCoverRemovalEnablesFurtherMerge() {
        // /26 children with hop A sit under a /25 with hop A (redundant); the merged /24 stays correct
        RouteTable t = table("10.0.0.0/25=A", "10.0.0.0/26=A", "10.0.0.128/25=A");
        assertEquals(List.of("10.0.0.0/24=A"), dump(t.aggregate()));
    }

    @Test
    void aggregateLeavesOriginalUntouched() {
        RouteTable t = table("10.0.0.0/25=A", "10.0.0.128/25=A");
        RouteTable agg = t.aggregate();
        assertEquals(2, t.size());
        assertEquals(1, agg.size());
        agg.add(Cidr.parse("1.0.0.0/8"), "Q");
        assertEquals("-", hop(t, "1.2.3.4"));
    }

    @Test
    void aggregateIsLosslessIdempotentAndNeverLarger() {
        Random r = new Random(99);
        int shrunk = 0;
        for (int t = 0; t < 200; t++) {
            RouteTable table = new RouteTable();
            for (int i = 0, n = 1 + r.nextInt(40); i < n; i++) table.add(randomCidr(r), "h" + r.nextInt(2));
            RouteTable agg = table.aggregate();
            assertTrue(agg.size() <= table.size());
            if (agg.size() < table.size()) shrunk++;
            assertTrue(table.equivalentTo(agg), "exact equivalence");
            assertTrue(agg.equivalentTo(table));
            for (int a = 0; a < W; a++) {
                assertEquals(table.lookup(BASE + a).map(RouteTable.Route::nextHop).orElse("-"),
                    agg.lookup(BASE + a).map(RouteTable.Route::nextHop).orElse("-"), "window address " + a);
            }
            assertEquals(dump(agg), dump(agg.aggregate()), "idempotent");
        }
        assertTrue(shrunk > 60, "aggregation should shrink many random tables, shrunk=" + shrunk);
    }

    @Test
    void equivalentToDetectsDifferences() {
        RouteTable a = table("10.0.0.0/8=A");
        assertTrue(a.equivalentTo(table("10.0.0.0/9=A", "10.128.0.0/9=A")));
        assertFalse(a.equivalentTo(table("10.0.0.0/8=B")));
        assertFalse(a.equivalentTo(table("10.0.0.0/9=A")));          // missing upper half
        assertFalse(a.equivalentTo(table("10.0.0.0/8=A", "10.255.255.255/32=B"))); // single-address difference at the end
        assertFalse(table("0.0.0.0/0=A").equivalentTo(table("0.0.0.0/1=A", "128.0.0.0/2=A")));
        assertTrue(new RouteTable().equivalentTo(new RouteTable()));
    }
}
