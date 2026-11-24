package ae.columnar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class TableTest {

    static final String[] COLS = {"ts", "user", "amount"};

    /** Generates rows: ts ascending (~10 apart), user low-cardinality, amount small signed noise. */
    static long[][] rows(int n, long seed) {
        Random rnd = new Random(seed);
        long[][] r = new long[n][];
        long ts = 1000;
        for (int i = 0; i < n; i++) {
            ts += 8 + rnd.nextInt(5);
            r[i] = new long[] {ts, rnd.nextInt(7) * 100L, rnd.nextInt(2001) - 1000L};
        }
        return r;
    }

    static Table build(long[][] rows, int segRows) {
        Table.Builder b = Table.builder(segRows, COLS);
        for (long[] r : rows) {
            b.append(r);
        }
        return b.build();
    }

    static int col(String c) {
        for (int i = 0; i < COLS.length; i++) {
            if (COLS[i].equals(c)) {
                return i;
            }
        }
        throw new AssertionError(c);
    }

    static List<Integer> naiveSelect(long[][] rows, List<Pred> preds) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            boolean ok = true;
            for (Pred p : preds) {
                ok &= p.op().test(rows[i][col(p.column())], p.value());
            }
            if (ok) {
                out.add(i);
            }
        }
        return out;
    }

    static List<Integer> asList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int x : a) {
            l.add(x);
        }
        return l;
    }

    static OptionalLong naiveAgg(long[][] rows, List<Integer> sel, Agg agg, String c) {
        if (agg == Agg.COUNT) {
            return OptionalLong.of(sel.size());
        }
        if (sel.isEmpty()) {
            return agg == Agg.SUM ? OptionalLong.of(0) : OptionalLong.empty();
        }
        long acc = agg == Agg.MIN ? Long.MAX_VALUE : agg == Agg.MAX ? Long.MIN_VALUE : 0;
        for (int i : sel) {
            long v = rows[i][col(c)];
            acc = switch (agg) {
                case SUM -> acc + v;
                case MIN -> Math.min(acc, v);
                case MAX -> Math.max(acc, v);
                case COUNT -> acc;
            };
        }
        return OptionalLong.of(acc);
    }

    // ---------------------------------------------------------------- literal tests

    @Test
    void segmentsAreCutEveryNRowsWithAShortTail() {
        long[][] r = rows(250, 1);
        Table t = build(r, 100);
        assertEquals(250, t.rowCount());
        assertEquals(3, t.segmentCount()); // 100 + 100 + 50
        for (int i = 0; i < 250; i++) {
            for (String c : COLS) {
                assertEquals(r[i][col(c)], t.get(c, i), c + " row " + i);
            }
        }
        assertThrows(IndexOutOfBoundsException.class, () -> t.get("ts", 250));
        assertThrows(IndexOutOfBoundsException.class, () -> t.get("ts", -1));
        assertThrows(IllegalArgumentException.class, () -> t.get("nope", 0));
    }

    @Test
    void sortedColumnPrunesSegmentsByZoneMap() {
        // ts = 0..999, 10 segments of 100; segment k holds [100k, 100k+99]
        Table.Builder b = Table.builder(100, "ts");
        for (long i = 0; i < 1000; i++) {
            b.append(i);
        }
        Table t = b.build();

        Selection ge950 = t.select(List.of(new Pred("ts", Op.GE, 950)));
        assertEquals(50, ge950.rows().length);
        assertEquals(950, ge950.rows()[0]);
        assertEquals(999, ge950.rows()[49]);
        assertEquals(9, ge950.segmentsPruned());
        assertEquals(0, ge950.segmentsAllMatch());
        assertEquals(1, ge950.segmentsScanned()); // segment 9 = [900,999] straddles 950

        Selection ge0 = t.select(List.of(new Pred("ts", Op.GE, 0)));
        assertEquals(1000, ge0.rows().length);
        assertEquals(0, ge0.segmentsPruned());
        assertEquals(10, ge0.segmentsAllMatch());
        assertEquals(0, ge0.segmentsScanned());

        Selection lt0 = t.select(List.of(new Pred("ts", Op.LT, 0)));
        assertEquals(0, lt0.rows().length);
        assertEquals(10, lt0.segmentsPruned());

        Selection eq = t.select(List.of(new Pred("ts", Op.EQ, 1234)));
        assertEquals(0, eq.rows().length);
        assertEquals(10, eq.segmentsPruned());

        Selection none = t.select(List.of());
        assertEquals(1000, none.rows().length); // no predicates: every row, every segment "all match"
        assertEquals(10, none.segmentsAllMatch());
    }

    @Test
    void conjunctionPrunesWhenEitherPredicateRulesTheSegmentOut() {
        // two columns: a ascending 0..199 (2 segments of 100), b constant 5
        Table.Builder b = Table.builder(100, "a", "b");
        for (long i = 0; i < 200; i++) {
            b.append(i, 5);
        }
        Table t = b.build();
        Selection s = t.select(List.of(new Pred("a", Op.LT, 50), new Pred("b", Op.EQ, 5)));
        assertEquals(50, s.rows().length);
        assertEquals(1, s.segmentsPruned());     // segment 1 pruned by a
        assertEquals(1, s.segmentsScanned());    // segment 0: a partial, b all -> scanned
        Selection s2 = t.select(List.of(new Pred("a", Op.GE, 0), new Pred("b", Op.NE, 5)));
        assertEquals(0, s2.rows().length);
        assertEquals(2, s2.segmentsPruned());    // b proves NONE although a is ALL
    }

    @Test
    void aggregatesOnLiteralData() {
        Table.Builder b = Table.builder(2, "g", "v");
        long[][] data = {{1, 10}, {2, 20}, {1, 30}, {2, -5}, {3, 7}};
        for (long[] r : data) {
            b.append(r);
        }
        Table t = b.build();
        List<Pred> all = List.of();
        assertEquals(OptionalLong.of(5), t.aggregate(all, Agg.COUNT, null));
        assertEquals(OptionalLong.of(62), t.aggregate(all, Agg.SUM, "v"));
        assertEquals(OptionalLong.of(-5), t.aggregate(all, Agg.MIN, "v"));
        assertEquals(OptionalLong.of(30), t.aggregate(all, Agg.MAX, "v"));
        List<Pred> g1 = List.of(new Pred("g", Op.EQ, 1));
        assertEquals(OptionalLong.of(40), t.aggregate(g1, Agg.SUM, "v"));
        List<Pred> nothing = List.of(new Pred("g", Op.EQ, 99));
        assertEquals(OptionalLong.of(0), t.aggregate(nothing, Agg.COUNT, null));
        assertEquals(OptionalLong.of(0), t.aggregate(nothing, Agg.SUM, "v"));
        assertEquals(OptionalLong.empty(), t.aggregate(nothing, Agg.MIN, "v"));
        assertEquals(OptionalLong.empty(), t.aggregate(nothing, Agg.MAX, "v"));

        SortedMap<Long, Long> expectSum = new TreeMap<>();
        expectSum.put(1L, 40L);
        expectSum.put(2L, 15L);
        expectSum.put(3L, 7L);
        assertEquals(expectSum, t.groupBy(all, "g", Agg.SUM, "v"));
        SortedMap<Long, Long> expectCount = new TreeMap<>();
        expectCount.put(1L, 2L);
        expectCount.put(2L, 2L);
        expectCount.put(3L, 1L);
        assertEquals(expectCount, t.groupBy(all, "g", Agg.COUNT, null));
        assertEquals(new TreeMap<Long, Long>(), t.groupBy(nothing, "g", Agg.MAX, "v"));
    }

    @Test
    void sumOverflowThrowsInsteadOfWrapping() {
        Table.Builder b = Table.builder(10, "v");
        b.append(Long.MAX_VALUE);
        b.append(1);
        Table t = b.build();
        assertThrows(ArithmeticException.class, () -> t.aggregate(List.of(), Agg.SUM, "v"));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), t.aggregate(List.of(new Pred("v", Op.GT, 1)), Agg.SUM, "v"));
    }

    @Test
    void emptyTableAndInvalidUse() {
        Table t = Table.builder(8, "x").build();
        assertEquals(0, t.rowCount());
        assertEquals(0, t.segmentCount());
        assertEquals(0, t.select(List.of(new Pred("x", Op.GT, 0))).rows().length);
        assertEquals(OptionalLong.of(0), t.aggregate(List.of(), Agg.COUNT, null));

        Table.Builder b = Table.builder(8, "x", "y");
        assertThrows(IllegalArgumentException.class, () -> b.append(1L));
        assertThrows(IllegalArgumentException.class, () -> b.append(1L, 2L, 3L));
        assertThrows(IllegalArgumentException.class, () -> Table.builder(0, "x"));
        assertThrows(IllegalArgumentException.class, () -> Table.builder(8));
        assertThrows(IllegalArgumentException.class, () -> Table.builder(8, "x", "x"));
        Table full = build(rows(10, 2), 4);
        assertThrows(IllegalArgumentException.class, () -> full.select(List.of(new Pred("bogus", Op.EQ, 1))));
        assertThrows(IllegalArgumentException.class, () -> full.aggregate(List.of(), Agg.SUM, "bogus"));
        assertThrows(IllegalArgumentException.class, () -> full.aggregate(List.of(), Agg.SUM, null));
        assertThrows(IllegalArgumentException.class, () -> full.groupBy(List.of(), "bogus", Agg.COUNT, null));
    }

    @Test
    void builderCannotBeReusedAfterBuild() {
        Table.Builder b = Table.builder(4, "x");
        b.append(1);
        Table t = b.build();
        assertThrows(IllegalStateException.class, () -> b.append(2));
        assertThrows(IllegalStateException.class, b::build);
        assertEquals(1, t.rowCount());
    }

    @Test
    void segmentsChooseDifferentEncodingsPerColumn() {
        long[][] r = rows(2000, 9);
        Table t = build(r, 1000);
        // ts is ascending with small steps -> DELTA; user has 7 distinct values -> DICT (or RLE never); amount ~11 bits -> FOR
        assertEquals(Encoding.DELTA, t.encodingOf("ts", 0));
        assertEquals(Encoding.DICT, t.encodingOf("user", 0));
        assertEquals(Encoding.FOR, t.encodingOf("amount", 0));
        assertThrows(IndexOutOfBoundsException.class, () -> t.encodingOf("ts", 2));
    }

    // ---------------------------------------------------------------- differential tests

    @Test
    void randomQueriesMatchNaiveRowScan() {
        int nonEmpty = 0;
        int pruned = 0;
        int queries = 0;
        for (long seed = 1; seed <= 30; seed++) {
            Random rnd = new Random(seed);
            int n = 1 + rnd.nextInt(1500);
            long[][] data = rows(n, seed);
            Table t = build(data, 1 + rnd.nextInt(300));
            assertEquals(n, t.rowCount());
            for (int q = 0; q < 30; q++) {
                List<Pred> preds = new ArrayList<>();
                int k = rnd.nextInt(4);
                for (int j = 0; j < k; j++) {
                    String c = COLS[rnd.nextInt(3)];
                    long[] sample = data[rnd.nextInt(n)];
                    long probe = sample[col(c)] + rnd.nextInt(3) - 1;
                    preds.add(new Pred(c, Op.values()[rnd.nextInt(Op.values().length)], probe));
                }
                List<Integer> expected = naiveSelect(data, preds);
                Selection s = t.select(preds);
                assertEquals(expected, asList(s.rows()), "seed " + seed + " q " + q + " " + preds);
                assertEquals(t.segmentCount(), s.segmentsPruned() + s.segmentsAllMatch() + s.segmentsScanned());
                queries++;
                if (!expected.isEmpty()) {
                    nonEmpty++;
                }
                pruned += s.segmentsPruned();
                for (Agg a : Agg.values()) {
                    assertEquals(naiveAgg(data, expected, a, "amount"), t.aggregate(preds, a, "amount"),
                        "agg " + a + " seed " + seed + " q " + q);
                }
                TreeMap<Long, List<Integer>> groups = new TreeMap<>();
                for (int i : expected) {
                    groups.computeIfAbsent(data[i][col("user")], x -> new ArrayList<>()).add(i);
                }
                for (Agg a : new Agg[] {Agg.COUNT, Agg.SUM, Agg.MIN, Agg.MAX}) {
                    SortedMap<Long, Long> want = new TreeMap<>();
                    groups.forEach((g, sel) -> want.put(g, naiveAgg(data, sel, a, "amount").getAsLong()));
                    assertEquals(want, t.groupBy(preds, "user", a, "amount"), "groupBy " + a + " seed " + seed + " q " + q);
                }
            }
        }
        assertTrue(queries == 900);
        assertTrue(nonEmpty > 300, "generator should yield many non-empty results, got " + nonEmpty);
        assertTrue(pruned > 200, "zone maps should have pruned segments, got " + pruned);
    }
}
