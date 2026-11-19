package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DmlTest extends SqlTestBase {
    @Test
    void createInsertSelectRoundTrip() {
        db.execute("CREATE TABLE t (a INT, b TEXT, c BOOL)");
        assertEquals(2, db.execute("INSERT INTO t VALUES (1, 'x', TRUE), (2, NULL, FALSE)").affected());
        assertEquals(List.of(r(1L, "x", true), r(2L, null, false)), rows("SELECT * FROM t"));
    }

    @Test
    void insertWithColumnListFillsOthersWithNull() {
        db.execute("CREATE TABLE t (a INT, b TEXT, c INT)");
        db.execute("INSERT INTO t (c, a) VALUES (3, 1)");
        assertEquals(List.of(r(1L, null, 3L)), rows("SELECT * FROM t"));
    }

    @Test
    void insertErrors() {
        db.execute("CREATE TABLE t (a INT NOT NULL, b TEXT)");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (1)"), "too few values");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (1, 'a', 3)"), "too many");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES ('x', 'a')"), "type");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (NULL, 'a')"), "not null");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t (b) VALUES ('a')"), "omitted not-null column");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t (a, a) VALUES (1, 2)"), "duplicate column");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t (zz) VALUES (1)"), "unknown column");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO nope VALUES (1)"), "unknown table");
        assertEquals(List.of(), rows("SELECT * FROM t"));
    }

    @Test
    void insertValuesMayUseConstantExpressions() {
        db.execute("CREATE TABLE t (a INT, b TEXT)");
        db.execute("INSERT INTO t VALUES (1 + 2, 'a' || 'b')");
        assertEquals(List.of(r(3L, "ab")), rows("SELECT * FROM t"));
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (a, 'x')"), "column ref not constant");
    }

    @Test
    void failingMultiRowInsertChangesNothing() {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, v TEXT)");
        db.execute("INSERT INTO t VALUES (1, 'a')");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (2, 'b'), (3, 'c'), (2, 'dup')"));
        assertEquals(List.of(r(1L, "a")), rows("SELECT * FROM t"));
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (1, 'again')"), "dup with existing row");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO t VALUES (NULL, 'n')"), "pk not null");
    }

    @Test
    void createTableErrors() {
        db.execute("CREATE TABLE t (a INT)");
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE t (a INT)"), "exists");
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE u (a INT, A TEXT)"), "dup column, case-insensitive");
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE u (a INT PRIMARY KEY, b INT PRIMARY KEY)"), "two pks");
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE u (a FLOAT)"), "unknown type");
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE u ()"));
    }

    @Test
    void identifiersAreCaseInsensitive() {
        db.execute("CREATE TABLE Items (Id INT, Name TEXT)");
        db.execute("insert into ITEMS values (1, 'a')");
        assertEquals(List.of(r(1L)), rows("SELECT ID FROM items WHERE name = 'a'"));
    }

    @Test
    void dropTable() {
        db.execute("CREATE TABLE t (a INT)");
        db.execute("DROP TABLE t");
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM t"));
        assertThrows(SqlException.class, () -> db.execute("DROP TABLE t"));
        db.execute("CREATE TABLE t (b INT)");
        assertEquals(List.of("b"), db.execute("SELECT * FROM t").columns());
    }

    @Test
    void updateCountsAndUsesOldRowValues() {
        people();
        assertEquals(2, db.execute("UPDATE p SET age = age + 1 WHERE city = 'oslo'").affected());
        assertEquals(List.of(r(31L), r(26L)), rows("SELECT age FROM p WHERE city = 'oslo' ORDER BY id"));
        // swap-style assignment: right sides see the OLD row
        db.execute("CREATE TABLE s (a INT, b INT)");
        db.execute("INSERT INTO s VALUES (1, 2)");
        db.execute("UPDATE s SET a = b, b = a");
        assertEquals(List.of(r(2L, 1L)), rows("SELECT * FROM s"));
    }

    @Test
    void updateWithoutWhereAndNullPredicateRows() {
        people();
        assertEquals(5, db.execute("UPDATE p SET name = UPPER(name)").affected());
        // age is NULL for bob so "age > 26" is NULL there and bob is not updated
        assertEquals(3, db.execute("UPDATE p SET city = 'x' WHERE age > 26").affected());
        assertEquals(List.of(r("x"), r("x"), r("x")), rows("SELECT city FROM p WHERE city = 'x'"));
    }

    @Test
    void failingUpdateIsAtomic() {
        people();
        // setting every id to 7 collides on the second row, nothing may change
        assertThrows(SqlException.class, () -> db.execute("UPDATE p SET id = 7"));
        assertEquals(List.of(r(1L), r(2L), r(3L), r(4L), r(5L)), rows("SELECT id FROM p ORDER BY id"));
        // error on a later row (name NOT NULL after row 2) rolls back row 1's change too
        assertThrows(SqlException.class, () -> db.execute("UPDATE p SET name = CASE_X"), "unknown column");
        assertThrows(SqlException.class, () -> db.execute("UPDATE p SET age = 100 / (id - 3)"), "division by zero at id 3");
        assertEquals(List.of(r(30L), r((Object) null), r(25L), r(30L), r(41L)), rows("SELECT age FROM p ORDER BY id"));
        assertThrows(SqlException.class, () -> db.execute("UPDATE p SET name = NULL WHERE id = 5"), "not null");
        assertThrows(SqlException.class, () -> db.execute("UPDATE p SET age = 'x'"), "type");
    }

    @Test
    void deleteCountsAndPredicate() {
        people();
        assertEquals(2, db.execute("DELETE FROM p WHERE city = 'rome'").affected());
        assertEquals(List.of(r(1L), r(3L), r(4L)), rows("SELECT id FROM p ORDER BY id"));
        assertEquals(3, db.execute("DELETE FROM p").affected());
        assertEquals(List.of(), rows("SELECT * FROM p"));
        assertEquals(0, db.execute("DELETE FROM p").affected());
    }

    @Test
    void primaryKeyFreedByDeleteCanBeReused() {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY)");
        db.execute("INSERT INTO t VALUES (1)");
        db.execute("DELETE FROM t WHERE id = 1");
        db.execute("INSERT INTO t VALUES (1)");
        assertEquals(1, rows("SELECT * FROM t").size());
    }

    @Test
    void updatePrimaryKeyToSwapValuesIsAllowedWhenFinalStateIsUnique() {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, v TEXT)");
        db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        db.execute("UPDATE t SET id = 3 - id");
        assertEquals(List.of(r(1L, "b"), r(2L, "a")), rows("SELECT * FROM t ORDER BY id"));
    }
}
