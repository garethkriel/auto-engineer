package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectTest extends SqlTestBase {
    @BeforeEach
    void setUp() {
        people();
    }

    @Test
    void starAndQualifiedStar() {
        Result res = db.execute("SELECT * FROM p WHERE id = 1");
        assertEquals(List.of("id", "name", "age", "city"), res.columns());
        assertEquals(List.of(r(1L, "ann", 30L, "oslo")), res.rows());
        assertEquals(List.of(r(1L, "ann", 30L, "oslo")), rows("SELECT p.* FROM p WHERE id = 1"));
        assertEquals(List.of("name", "age", "city", "id"), db.execute("SELECT name, age, city, id FROM p").columns());
    }

    @Test
    void whereKeepsOnlyTrueRows() {
        assertEquals(List.of(r(1L), r(4L)), rows("SELECT id FROM p WHERE age = 30 ORDER BY id"));
        // bob (age NULL) is in neither the predicate nor its negation
        assertEquals(List.of(r(3L)), rows("SELECT id FROM p WHERE NOT age >= 30"));
        assertEquals(List.of(r(2L)), rows("SELECT id FROM p WHERE age IS NULL"));
        assertEquals(List.of(r(4L)), rows("SELECT id FROM p WHERE city IS NULL"));
    }

    @Test
    void orderByNullsFirstAscLastDescAndStable() {
        assertEquals(List.of(r(2L), r(3L), r(1L), r(4L), r(5L)), rows("SELECT id FROM p ORDER BY age, id"));
        assertEquals(List.of(r(5L), r(4L), r(1L), r(3L), r(2L)), rows("SELECT id FROM p ORDER BY age DESC, id DESC"));
        // stability: ties on age keep insertion order (1 before 4)
        assertEquals(List.of(r(1L), r(4L)), rows("SELECT id FROM p WHERE age = 30 ORDER BY age"));
        assertEquals(List.of(r(4L), r(3L), r(1L), r(5L), r(2L)), rows("SELECT id FROM p ORDER BY city, id DESC"));
    }

    @Test
    void orderByAliasPositionAndExpression() {
        assertEquals(List.of(r("eve"), r("dee")), rows("SELECT name AS n FROM p ORDER BY n DESC LIMIT 2"));
        assertEquals(List.of(r("ann", 30L), r("bob", null)), rows("SELECT name, age FROM p ORDER BY 1 LIMIT 2"));
        assertEquals(List.of(r(5L), r(4L)), rows("SELECT id FROM p ORDER BY -id LIMIT 2"));
        assertEquals(List.of(r(3L)), rows("SELECT id FROM p ORDER BY LENGTH(name), id LIMIT 1"), "cy is the shortest name");
        assertThrows(SqlException.class, () -> db.execute("SELECT id FROM p ORDER BY 2"));
        assertThrows(SqlException.class, () -> db.execute("SELECT id FROM p ORDER BY nosuch"));
    }

    @Test
    void limitAndOffset() {
        assertEquals(List.of(r(2L), r(3L)), rows("SELECT id FROM p ORDER BY id LIMIT 2 OFFSET 1"));
        assertEquals(List.of(r(5L)), rows("SELECT id FROM p ORDER BY id LIMIT 10 OFFSET 4"));
        assertEquals(List.of(), rows("SELECT id FROM p ORDER BY id LIMIT 10 OFFSET 5"));
        assertEquals(List.of(), rows("SELECT id FROM p LIMIT 0"));
        assertThrows(SqlException.class, () -> db.execute("SELECT id FROM p LIMIT -1"));
    }

    @Test
    void distinctKeepsFirstOccurrenceAndTreatsNullsAsEqual() {
        assertEquals(List.of(r("oslo"), r("rome"), r((Object) null)), rows("SELECT DISTINCT city FROM p ORDER BY id LIMIT 10"));
        assertEquals(List.of(r(30L), r((Object) null), r(25L), r(41L)), rows("SELECT DISTINCT age FROM p"));
    }

    @Test
    void inLikeBetweenInWhere() {
        assertEquals(List.of(r(1L), r(3L)), rows("SELECT id FROM p WHERE name IN ('ann', 'cy', 'zed') ORDER BY id"));
        assertEquals(List.of(r(1L), r(4L), r(5L)), rows("SELECT id FROM p WHERE age BETWEEN 30 AND 41 ORDER BY id"));
        assertEquals(List.of(r(2L)), rows("SELECT id FROM p WHERE name LIKE 'b%'"));
        assertEquals(List.of(r(1L), r(3L)), rows("SELECT id FROM p WHERE city LIKE '_s%' ORDER BY id"));
    }

    @Test
    void projectionExpressionsAndAliasScope() {
        assertEquals(List.of(r("ANN-oslo", 31L)), rows("SELECT UPPER(name) || '-' || city, age + 1 FROM p WHERE id = 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT name || age FROM p"), "|| needs text");
        assertThrows(SqlException.class, () -> db.execute("SELECT id FROM p WHERE n = 'x'"));
        assertThrows(SqlException.class, () -> db.execute("SELECT name AS n FROM p WHERE n = 'ann'"), "aliases are not visible in WHERE");
    }

    @Test
    void unknownColumnAndTable() {
        SqlException e = assertThrows(SqlException.class, () -> db.execute("SELECT zzz FROM p"));
        assertTrue(e.getMessage().contains("zzz"));
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM nope"));
        assertThrows(SqlException.class, () -> db.execute("SELECT q.id FROM p"));
    }

    @Test
    void selectWithoutFromOneRow() {
        Result res = db.execute("SELECT 1 AS one, 'x'");
        assertEquals(List.of("one", "'x'"), res.columns());
        assertEquals(List.of(r(1L, "x")), res.rows());
        assertThrows(SqlException.class, () -> db.execute("SELECT *"));
        assertEquals(List.of(), rows("SELECT 1 WHERE FALSE"));
    }

    @Test
    void tableAliasHidesTableName() {
        assertEquals(List.of(r(1L)), rows("SELECT x.id FROM p x WHERE x.id = 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT p.id FROM p x"));
    }
}
