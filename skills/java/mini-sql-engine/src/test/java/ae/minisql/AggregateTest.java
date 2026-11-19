package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AggregateTest extends SqlTestBase {
    @BeforeEach
    void setUp() {
        people();
    }

    @Test
    void wholeTableAggregates() {
        // ages: 30, NULL, 25, 30, 41
        assertEquals(List.of(r(5L, 4L, 126L, 25L, 41L, 31.5)),
                rows("SELECT COUNT(*), COUNT(age), SUM(age), MIN(age), MAX(age), AVG(age) FROM p"));
    }

    @Test
    void emptyInputStillYieldsOneGroup() {
        assertEquals(List.of(r(0L, 0L, null, null, null, null)),
                rows("SELECT COUNT(*), COUNT(age), SUM(age), MIN(age), MAX(age), AVG(age) FROM p WHERE id > 99"));
        assertEquals(List.of(), rows("SELECT city, COUNT(*) FROM p WHERE id > 99 GROUP BY city"));
    }

    @Test
    void groupByFirstSeenOrderWithNullGroup() {
        assertEquals(List.of(r("oslo", 2L), r("rome", 2L), r(null, 1L)),
                rows("SELECT city, COUNT(*) FROM p GROUP BY city"));
        assertEquals(List.of(r("oslo", 55L), r("rome", 41L), r(null, 30L)),
                rows("SELECT city, SUM(age) FROM p GROUP BY city"));
    }

    @Test
    void havingAndOrderByAggregates() {
        assertEquals(List.of(r("oslo"), r("rome")), rows("SELECT city FROM p GROUP BY city HAVING COUNT(*) > 1 ORDER BY city"));
    }

    @Test
    void orderByDescNumericAggregateAlias() {
        assertEquals(List.of(r("rome", 2L), r("oslo", 2L), r(null, 1L)),
                rows("SELECT city, COUNT(*) AS n FROM p GROUP BY city ORDER BY n DESC, city DESC"));
        // MIN(age) per city: null-group 30, oslo 25, rome 41
        assertEquals(List.of(r("oslo"), r((Object) null)), rows("SELECT city FROM p GROUP BY city ORDER BY MIN(age) LIMIT 2"));
    }

    @Test
    void aggregateOverExpressionAndInsideExpression() {
        // oslo (30, 25) and the NULL-city row (30): ages 30 + 25 + 30 = 85
        assertEquals(List.of(r(170L, 170L)), rows("SELECT SUM(age * 2), SUM(age) * 2 FROM p WHERE city = 'oslo' OR city IS NULL"));
    }

    @Test
    void columnsOutsideAggregatesMustBeGrouped() {
        assertThrows(SqlException.class, () -> db.execute("SELECT city, name, COUNT(*) FROM p GROUP BY city"));
        assertThrows(SqlException.class, () -> db.execute("SELECT name, COUNT(*) FROM p"));
        assertThrows(SqlException.class, () -> db.execute("SELECT city FROM p GROUP BY city HAVING age > 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT city FROM p GROUP BY city ORDER BY age"));
    }

    @Test
    void aggregateMisuseIsRejected() {
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM p WHERE COUNT(*) > 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT SUM(COUNT(*)) FROM p"));
        assertThrows(SqlException.class, () -> db.execute("SELECT SUM(name) FROM p"));
        assertThrows(SqlException.class, () -> db.execute("SELECT SUM(*) FROM p"));
        assertThrows(SqlException.class, () -> db.execute("SELECT COUNT(1, 2) FROM p"));
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM p GROUP BY city"), "star with group by");
    }

    @Test
    void minMaxWorkOnTextAndSumOverflowIsDetected() {
        assertEquals(List.of(r("ann", "eve")), rows("SELECT MIN(name), MAX(name) FROM p"));
        db.execute("CREATE TABLE big (v INT)");
        db.execute("INSERT INTO big VALUES (9223372036854775807), (1)");
        assertThrows(SqlException.class, () -> db.execute("SELECT SUM(v) FROM big"));
    }

    @Test
    void groupByQualifiedColumnAndMultipleKeys() {
        assertEquals(List.of(r("oslo", 30L, 1L), r("rome", null, 1L), r("oslo", 25L, 1L), r(null, 30L, 1L), r("rome", 41L, 1L)),
                rows("SELECT p.city, p.age, COUNT(*) FROM p GROUP BY p.city, age"));
    }
}
