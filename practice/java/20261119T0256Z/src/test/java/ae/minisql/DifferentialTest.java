package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Random predicates: SQL text plus a Java oracle that implements three-valued logic independently. */
class DifferentialTest extends SqlTestBase {
    /** Row is {a, b, c}: Long values (possibly null). */
    private record Row(Long a, Long b, Long c) {
        Long get(int i) {
            return i == 0 ? a : i == 1 ? b : c;
        }
    }

    /** A generated predicate: text and a 3VL oracle (Boolean null = unknown). */
    private record Pred(String sql, Function<Row, Boolean> eval) {}

    private static final String[] COLS = {"a", "b", "c"};

    private final Random rnd = new Random(20261119L);

    private Pred leaf() {
        int col = rnd.nextInt(3);
        String name = COLS[col];
        long k = rnd.nextInt(7) - 3;
        switch (rnd.nextInt(6)) {
            case 0:
                return new Pred(name + " = " + k, row -> row.get(col) == null ? null : row.get(col) == k);
            case 1:
                return new Pred(name + " < " + k, row -> row.get(col) == null ? null : row.get(col) < k);
            case 2:
                return new Pred(name + " IS NULL", row -> row.get(col) == null);
            case 3:
                return new Pred(name + " IS NOT NULL", row -> row.get(col) != null);
            case 4: {
                long k2 = k + rnd.nextInt(4);
                return new Pred(name + " BETWEEN " + k + " AND " + k2,
                        row -> row.get(col) == null ? null : row.get(col) >= k && row.get(col) <= k2);
            }
            default: {
                int other = rnd.nextInt(3);
                return new Pred(name + " >= " + COLS[other] + " + 1", row -> {
                    Long x = row.get(col);
                    Long y = row.get(other);
                    return x == null || y == null ? null : x >= y + 1;
                });
            }
        }
    }

    private Pred pred(int depth) {
        if (depth == 0 || rnd.nextInt(4) == 0) {
            return leaf();
        }
        switch (rnd.nextInt(3)) {
            case 0: {
                Pred p = pred(depth - 1);
                return new Pred("NOT (" + p.sql() + ")", row -> {
                    Boolean v = p.eval().apply(row);
                    return v == null ? null : !v;
                });
            }
            case 1: {
                Pred p = pred(depth - 1);
                Pred q = pred(depth - 1);
                return new Pred("(" + p.sql() + " AND " + q.sql() + ")", row -> {
                    Boolean x = p.eval().apply(row);
                    Boolean y = q.eval().apply(row);
                    if (Boolean.FALSE.equals(x) || Boolean.FALSE.equals(y)) {
                        return false;
                    }
                    return x == null || y == null ? null : true;
                });
            }
            default: {
                Pred p = pred(depth - 1);
                Pred q = pred(depth - 1);
                return new Pred("(" + p.sql() + " OR " + q.sql() + ")", row -> {
                    Boolean x = p.eval().apply(row);
                    Boolean y = q.eval().apply(row);
                    if (Boolean.TRUE.equals(x) || Boolean.TRUE.equals(y)) {
                        return true;
                    }
                    return x == null || y == null ? null : false;
                });
            }
        }
    }

    private List<Row> load(int n) {
        db.execute("CREATE TABLE t (k INT PRIMARY KEY, a INT, b INT, c INT)");
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Row row = new Row(maybe(), maybe(), maybe());
            data.add(row);
            db.execute("INSERT INTO t VALUES (" + i + ", " + lit(row.a()) + ", " + lit(row.b()) + ", " + lit(row.c()) + ")");
        }
        return data;
    }

    private Long maybe() {
        return rnd.nextInt(4) == 0 ? null : (long) (rnd.nextInt(7) - 3);
    }

    private static String lit(Long v) {
        return v == null ? "NULL" : v.toString();
    }

    @Test
    void whereAgreesWithOracleOnRandomPredicates() {
        List<Row> data = load(60);
        for (int round = 0; round < 300; round++) {
            Pred p = pred(3);
            List<List<Object>> expected = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                if (Boolean.TRUE.equals(p.eval().apply(data.get(i)))) {
                    expected.add(r((long) i));
                }
            }
            assertEquals(expected, rows("SELECT k FROM t WHERE " + p.sql() + " ORDER BY k"), p.sql());
        }
    }

    @Test
    void deleteAndUpdateAffectExactlyTheOracleRows() {
        List<Row> data = load(40);
        for (int round = 0; round < 60; round++) {
            Pred p = pred(2);
            long expectedCount = 0;
            for (Row row : data) {
                if (Boolean.TRUE.equals(p.eval().apply(row))) {
                    expectedCount++;
                }
            }
            db.execute("BEGIN");
            assertEquals(expectedCount, db.execute("UPDATE t SET a = 100 WHERE " + p.sql()).affected(), p.sql());
            assertEquals(expectedCount, rows("SELECT k FROM t WHERE a = 100").size(), "all matched rows updated: " + p.sql());
            db.execute("ROLLBACK");
            // a separate transaction: the update above changed a, which the predicate may read
            db.execute("BEGIN");
            assertEquals(expectedCount, db.execute("DELETE FROM t WHERE " + p.sql()).affected(), p.sql());
            assertEquals(data.size() - expectedCount, rows("SELECT * FROM t").size(), p.sql());
            db.execute("ROLLBACK");
            assertEquals(data.size(), rows("SELECT * FROM t").size());
        }
    }

    @Test
    void orderByAgreesWithComparatorOracle() {
        List<Row> data = load(50);
        for (int round = 0; round < 40; round++) {
            boolean ascA = rnd.nextBoolean();
            boolean ascB = rnd.nextBoolean();
            Comparator<Long> nullsFirst = Comparator.nullsFirst(Comparator.naturalOrder());
            Comparator<Row> byA = Comparator.comparing(Row::a, ascA ? nullsFirst : nullsFirst.reversed());
            Comparator<Row> byB = Comparator.comparing(Row::b, ascB ? nullsFirst : nullsFirst.reversed());
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                idx.add(i);
            }
            idx.sort(Comparator.comparing((Integer i) -> data.get(i), byA.thenComparing(byB)));
            List<List<Object>> expected = new ArrayList<>();
            for (int i : idx) {
                expected.add(r((long) i));
            }
            String sql = "SELECT k FROM t ORDER BY a " + (ascA ? "ASC" : "DESC") + ", b " + (ascB ? "ASC" : "DESC");
            assertEquals(expected, rows(sql), sql);
        }
    }

    @Test
    void groupedAggregatesAgreeWithOracle() {
        List<Row> data = load(80);
        long[] count = new long[7];
        long[] cntB = new long[7];
        long[] sumB = new long[7];
        boolean[] seen = new boolean[7];
        for (Row row : data) {
            if (row.a() == null) {
                continue;
            }
            int g = (int) (row.a() + 3);
            seen[g] = true;
            count[g]++;
            if (row.b() != null) {
                cntB[g]++;
                sumB[g] += row.b();
            }
        }
        List<List<Object>> expected = new ArrayList<>();
        for (int g = 0; g < 7; g++) {
            if (seen[g]) {
                Object sum = null;
                if (cntB[g] > 0) {
                    sum = sumB[g];
                }
                expected.add(r(g - 3L, count[g], cntB[g], sum));
            }
        }
        assertEquals(expected,
                rows("SELECT a, COUNT(*), COUNT(b), SUM(b) FROM t WHERE a IS NOT NULL GROUP BY a ORDER BY a"));
    }
}
