package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Randomised NAIVE-vs-OPTIMIZED differential tests plus an independent stream-based oracle. */
class DifferentialTest {

    private static final String[] STR = {"x", "y", "xy", "yx"};

    /** Three tables t0..t2 with columns a INT, b INT, s TEXT, f BOOL, nullable, tiny value domains (so joins hit). */
    private static Database randomDb(Random rnd) {
        Database db = new Database();
        for (int t = 0; t < 3; t++) {
            db.execute("create table t" + t + " (a int, b int, s text, f bool)");
            int n = rnd.nextInt(9); // 0..8 rows, empty tables included
            for (int i = 0; i < n; i++) {
                db.execute("insert into t" + t + " values (" + nullable(rnd, "" + rnd.nextInt(4)) + ", "
                        + nullable(rnd, "" + rnd.nextInt(4)) + ", " + nullable(rnd, "'" + STR[rnd.nextInt(4)] + "'")
                        + ", " + nullable(rnd, rnd.nextBoolean() ? "true" : "false") + ")");
            }
        }
        return db;
    }

    private static String nullable(Random rnd, String v) {
        return rnd.nextInt(6) == 0 ? "null" : v;
    }

    private static String intCol(Random rnd, int rels) {
        return "q" + rnd.nextInt(rels) + "." + (rnd.nextBoolean() ? "a" : "b");
    }

    private static String intExpr(Random rnd, int rels, int depth) {
        int c = depth <= 0 ? rnd.nextInt(2) : rnd.nextInt(5);
        return switch (c) {
            case 0, 1 -> rnd.nextInt(4) == 0 ? "" + rnd.nextInt(4) : intCol(rnd, rels);
            case 2 -> "(" + intExpr(rnd, rels, depth - 1) + " + " + intExpr(rnd, rels, depth - 1) + ")";
            case 3 -> "(" + intExpr(rnd, rels, depth - 1) + " - 1)";
            default -> "(" + intExpr(rnd, rels, depth - 1) + " * 2)";
        };
    }

    private static String boolExpr(Random rnd, int rels, int depth) {
        int c = depth <= 0 ? rnd.nextInt(6) : rnd.nextInt(10);
        String strCol = "q" + rnd.nextInt(rels) + ".s";
        return switch (c) {
            case 0, 1 -> intExpr(rnd, rels, 1) + " " + new String[] {"=", "<>", "<", "<=", ">", ">="}[rnd.nextInt(6)] + " "
                    + intExpr(rnd, rels, 1);
            case 2 -> strCol + " = '" + STR[rnd.nextInt(4)] + "'";
            case 3 -> strCol + (rnd.nextBoolean() ? " like " : " not like ") + "'" + new String[] {"x%", "%y", "_y", "%"}[rnd.nextInt(4)] + "'";
            case 4 -> intCol(rnd, rels) + (rnd.nextBoolean() ? " is null" : " is not null");
            case 5 -> "q" + rnd.nextInt(rels) + ".f";
            case 6 -> "not (" + boolExpr(rnd, rels, depth - 1) + ")";
            case 7 -> "(" + boolExpr(rnd, rels, depth - 1) + " and " + boolExpr(rnd, rels, depth - 1) + ")";
            case 8 -> "(" + boolExpr(rnd, rels, depth - 1) + " or " + boolExpr(rnd, rels, depth - 1) + ")";
            default -> intCol(rnd, rels) + (rnd.nextBoolean() ? " in (" : " not in (") + rnd.nextInt(4) + ", " + nullable(rnd, "" + rnd.nextInt(4)) + ")";
        };
    }

    /** Returns one random SELECT; rows are totally ordered by ORDER BY over every output column. */
    private static String randomQuery(Random rnd) {
        int rels = 1 + rnd.nextInt(3);
        StringBuilder from = new StringBuilder("from t" + rnd.nextInt(3) + " q0");
        for (int i = 1; i < rels; i++) {
            String on = rnd.nextInt(5) == 0
                    ? boolExpr(rnd, i + 1, 1)
                    : "q" + rnd.nextInt(i) + "." + (rnd.nextBoolean() ? "a" : "b") + " = q" + i + "." + (rnd.nextBoolean() ? "a" : "b")
                            + (rnd.nextInt(3) == 0 ? " and " + boolExpr(rnd, i + 1, 1) : "");
            from.append(rnd.nextInt(3) == 0 ? " left join " : " join ").append("t").append(rnd.nextInt(3)).append(" q").append(i).append(" on ").append(on);
        }
        String where = rnd.nextInt(3) == 0 ? "" : " where " + boolExpr(rnd, rels, 2);
        StringBuilder sb = new StringBuilder("select ");
        int items;
        if (rnd.nextInt(3) == 0) { // grouped
            List<String> keys = new ArrayList<>();
            for (int k = 1 + rnd.nextInt(2); k > 0; k--) keys.add(intCol(rnd, rels));
            List<String> sel = new ArrayList<>(keys);
            for (int k = 1 + rnd.nextInt(3); k > 0; k--) {
                sel.add(new String[] {"count(*)", "count(" + intCol(rnd, rels) + ")", "sum(" + intExpr(rnd, rels, 1) + ")",
                        "min(" + intCol(rnd, rels) + ")", "max(q0.s)", "count(distinct " + intCol(rnd, rels) + ")"}[rnd.nextInt(6)]);
            }
            items = sel.size();
            sb.append(String.join(", ", sel)).append(' ').append(from).append(where).append(" group by ").append(String.join(", ", keys));
            if (rnd.nextInt(3) == 0) sb.append(" having count(*) > ").append(rnd.nextInt(3));
        } else {
            List<String> sel = new ArrayList<>();
            for (int k = 1 + rnd.nextInt(4); k > 0; k--) {
                sel.add(rnd.nextInt(3) == 0 ? intExpr(rnd, rels, 2) : rnd.nextBoolean() ? "q" + rnd.nextInt(rels) + ".s" : intCol(rnd, rels));
            }
            items = sel.size();
            if (rnd.nextInt(4) == 0) sb.append("distinct ");
            sb.append(String.join(", ", sel)).append(' ').append(from).append(where);
        }
        sb.append(" order by ");
        for (int i = 1; i <= items; i++) sb.append(i == 1 ? "" : ", ").append(i).append(rnd.nextInt(4) == 0 ? " desc" : "");
        if (rnd.nextInt(4) == 0) sb.append(" limit ").append(rnd.nextInt(6)).append(rnd.nextBoolean() ? " offset 1" : "");
        return sb.toString();
    }

    @Test
    void plannersAgreeOnRandomQueries() {
        int nonEmpty = 0, hashJoins = 0, leftJoins = 0, grouped = 0, queries = 0, pushed = 0;
        for (int seed = 0; seed < 150; seed++) {
            Random rnd = new Random(seed);
            Database db = randomDb(rnd);
            for (int q = 0; q < 12; q++) {
                String sql = randomQuery(rnd);
                db.setPlanner(Planner.NAIVE);
                Result naive = db.execute(sql);
                String naivePlan = String.join("|", db.lastPlan());
                db.setPlanner(Planner.OPTIMIZED);
                Result opt = db.execute(sql);
                String optPlan = String.join("|", db.lastPlan());
                String ctx = "seed=" + seed + " q=" + q + " sql=" + sql;
                assertEquals(naive.columns(), opt.columns(), ctx);
                assertEquals(naive.rows(), opt.rows(), ctx);
                assertFalse(naivePlan.contains("hash-join"), ctx);
                queries++;
                if (!opt.rows().isEmpty()) nonEmpty++;
                if (optPlan.contains("hash-join")) hashJoins++;
                if (sql.contains(" left join ")) leftJoins++;
                if (sql.contains(" group by ")) grouped++;
                if (optPlan.matches(".*scan \\w+ as q\\d filter=[1-9].*")) pushed++;
            }
        }
        // outcome mix: the generator must actually reach the interesting paths
        assertEquals(1800, queries);
        assertTrue(nonEmpty > queries * 0.35, "non-empty results " + nonEmpty);
        assertTrue(hashJoins > 300, "hash joins " + hashJoins);
        assertTrue(leftJoins > 150, "left joins " + leftJoins);
        assertTrue(grouped > 300, "grouped " + grouped);
        assertTrue(pushed > 300, "pushdown " + pushed);
    }

    /** Independent oracle: plain collections, no SQL machinery. Inner equi-join + group + sum + count. */
    @Test
    void joinGroupAggregateMatchesCollectionsOracle() {
        for (int seed = 0; seed < 60; seed++) {
            Random rnd = new Random(1000 + seed);
            Database db = new Database();
            db.execute("create table l (k int, v int)");
            db.execute("create table r (k int, w int)");
            List<Long[]> l = new ArrayList<>(), r = new ArrayList<>();
            for (int i = rnd.nextInt(15); i >= 0; i--) {
                Long[] row = {rnd.nextInt(5) == 0 ? null : (long) rnd.nextInt(4), rnd.nextInt(4) == 0 ? null : (long) rnd.nextInt(10)};
                l.add(row);
                db.execute("insert into l values (" + row[0] + ", " + row[1] + ")");
            }
            for (int i = rnd.nextInt(15); i >= 0; i--) {
                Long[] row = {rnd.nextInt(5) == 0 ? null : (long) rnd.nextInt(4), (long) rnd.nextInt(10)};
                r.add(row);
                db.execute("insert into r values (" + row[0] + ", " + row[1] + ")");
            }
            // oracle: group by l.k over matching pairs -> (count(*), sum(l.v), max(r.w))
            Map<Long, long[]> groups = new TreeMap<>(); // [count, sum, sumNonNull?1:0, max]
            for (Long[] a : l) for (Long[] b : r) {
                if (a[0] == null || b[0] == null || !a[0].equals(b[0])) continue;
                long[] g = groups.computeIfAbsent(a[0], x -> new long[] {0, 0, 0, Long.MIN_VALUE});
                g[0]++;
                if (a[1] != null) { g[1] += a[1]; g[2] = 1; }
                g[3] = Math.max(g[3], b[1]);
            }
            List<List<Object>> want = new ArrayList<>();
            for (Map.Entry<Long, long[]> e : groups.entrySet()) {
                long[] g = e.getValue();
                want.add(Fx.row(e.getKey(), g[0], g[2] == 1 ? (Object) g[1] : null, g[3]));
            }
            for (Planner p : Planner.values()) {
                db.setPlanner(p);
                assertEquals(want, db.execute("select l.k, count(*), sum(l.v), max(r.w) from l join r on l.k = r.k group by l.k order by l.k").rows(),
                        "seed " + seed + " " + p);
            }
        }
    }

    @Test
    void leftJoinOracle() {
        for (int seed = 0; seed < 60; seed++) {
            Random rnd = new Random(5000 + seed);
            Database db = new Database();
            db.execute("create table l (k int, v int)");
            db.execute("create table r (k int, w int)");
            List<Long[]> l = new ArrayList<>(), r = new ArrayList<>();
            for (int i = rnd.nextInt(8); i >= 0; i--) {
                Long[] row = {rnd.nextInt(5) == 0 ? null : (long) rnd.nextInt(4), (long) i};
                l.add(row);
                db.execute("insert into l values (" + row[0] + ", " + row[1] + ")");
            }
            for (int i = rnd.nextInt(8); i >= 0; i--) {
                Long[] row = {rnd.nextInt(5) == 0 ? null : (long) rnd.nextInt(4), (long) (100 + i)};
                r.add(row);
                db.execute("insert into r values (" + row[0] + ", " + row[1] + ")");
            }
            // l LEFT JOIN r ON l.k = r.k AND r.w >= 103, rows (l.v, r.w); NULL-padded when no pair passes
            List<List<Object>> want = new ArrayList<>();
            for (Long[] a : l) {
                boolean any = false;
                for (Long[] b : r) {
                    if (a[0] != null && a[0].equals(b[0]) && b[1] >= 103) { want.add(Fx.row(a[1], b[1])); any = true; }
                }
                if (!any) want.add(Fx.row(a[1], null));
            }
            want.sort((x, y) -> {
                int c = Long.compare((Long) x.get(0), (Long) y.get(0));
                if (c != 0) return c;
                if (x.get(1) == null || y.get(1) == null) return x.get(1) == null ? (y.get(1) == null ? 0 : -1) : 1;
                return Long.compare((Long) x.get(1), (Long) y.get(1));
            });
            for (Planner p : Planner.values()) {
                db.setPlanner(p);
                assertEquals(want, db.execute("select l.v, r.w from l left join r on l.k = r.k and r.w >= 103 order by l.v, r.w").rows(),
                        "seed " + seed + " " + p);
            }
        }
    }

    @Test
    void largeEquiJoinIsFastWithHashJoin() {
        Database db = new Database();
        db.execute("create table big1 (k int, v int)");
        db.execute("create table big2 (k int, w int)");
        StringBuilder a = new StringBuilder("insert into big1 values "), b = new StringBuilder("insert into big2 values ");
        int n = 3000;
        for (int i = 0; i < n; i++) {
            a.append(i == 0 ? "" : ",").append("(").append(i).append(",").append(i % 7).append(")");
            b.append(i == 0 ? "" : ",").append("(").append(n - 1 - i).append(",").append(i % 5).append(")");
        }
        db.execute(a.toString());
        db.execute(b.toString());
        db.setPlanner(Planner.OPTIMIZED);
        long t0 = System.nanoTime();
        Result r = db.execute("select count(*), sum(v + w) from big1 join big2 on big1.k = big2.k");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(1, r.rows().size());
        assertEquals(3000L, r.rows().get(0).get(0));
        assertTrue(ms < 3000, "hash join over " + n + "x" + n + " took " + ms + " ms");
    }
}
