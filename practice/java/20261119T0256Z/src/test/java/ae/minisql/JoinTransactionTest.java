package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JoinTransactionTest extends SqlTestBase {
    @BeforeEach
    void setUp() {
        db.executeScript("""
            CREATE TABLE u (id INT PRIMARY KEY, name TEXT);
            CREATE TABLE o (id INT PRIMARY KEY, uid INT, amount INT);
            INSERT INTO u VALUES (1, 'ann'), (2, 'bob'), (3, 'cy');
            INSERT INTO o VALUES (10, 1, 5), (11, 1, 7), (12, 3, 2), (13, NULL, 9), (14, 99, 1);
            """);
    }

    @Test
    void innerJoinProducesMatchingPairsInLeftThenRightOrder() {
        assertEquals(List.of(r("ann", 10L), r("ann", 11L), r("cy", 12L)),
                rows("SELECT u.name, o.id FROM u JOIN o ON o.uid = u.id"));
        assertEquals(List.of(r("ann", 10L), r("ann", 11L), r("cy", 12L)),
                rows("SELECT u.name, o.id FROM u INNER JOIN o ON o.uid = u.id"));
    }

    @Test
    void leftJoinNullExtendsUnmatchedLeftRows() {
        assertEquals(List.of(r("ann", 10L), r("ann", 11L), r("bob", null), r("cy", 12L)),
                rows("SELECT u.name, o.id FROM u LEFT JOIN o ON o.uid = u.id"));
    }

    @Test
    void joinOnNullKeyNeverMatches() {
        assertEquals(List.of(), rows("SELECT * FROM o a JOIN o b ON a.uid = b.uid WHERE a.id = 13"));
    }

    @Test
    void onClauseCanBeAnyBooleanExpression() {
        assertEquals(List.of(r("ann", 11L)), rows("SELECT u.name, o.id FROM u JOIN o ON o.uid = u.id AND o.amount > 5"));
        // LEFT JOIN: the filter in ON keeps bob and ann/cy as unmatched rows
        assertEquals(List.of(r("ann", 11L), r("bob", null), r("cy", null)),
                rows("SELECT u.name, o.id FROM u LEFT JOIN o ON o.uid = u.id AND o.amount > 5"));
        // the same filter in WHERE removes the null-extended rows
        assertEquals(List.of(r("ann", 11L)),
                rows("SELECT u.name, o.id FROM u LEFT JOIN o ON o.uid = u.id WHERE o.amount > 5"));
    }

    @Test
    void ambiguousAndQualifiedColumns() {
        SqlException e = assertThrows(SqlException.class, () -> db.execute("SELECT id FROM u JOIN o ON o.uid = u.id"));
        assertTrue(e.getMessage().toLowerCase().contains("ambiguous"), e.getMessage());
        assertEquals(List.of(r("ann")), rows("SELECT name FROM u JOIN o ON o.uid = u.id WHERE amount = 5"));
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM u JOIN u ON u.id = u.id"), "duplicate alias");
        assertEquals(List.of(r(1L, 2L)), rows("SELECT a.id, b.id FROM u a JOIN u b ON b.id = a.id + 1 LIMIT 1"));
    }

    @Test
    void starOverJoinListsAllColumnsInOrder() {
        Result res = db.execute("SELECT * FROM u JOIN o ON o.uid = u.id WHERE o.id = 12");
        assertEquals(List.of("id", "name", "id", "uid", "amount"), res.columns());
        assertEquals(List.of(r(3L, "cy", 12L, 3L, 2L)), res.rows());
        assertEquals(List.of(r(12L, 3L, 2L)), rows("SELECT o.* FROM u JOIN o ON o.uid = u.id WHERE o.id = 12"));
    }

    @Test
    void groupedJoinWithLeftSideOnlyGroups() {
        assertEquals(List.of(r("ann", 2L, 12L), r("bob", 0L, null), r("cy", 1L, 2L)),
                rows("SELECT u.name, COUNT(o.id), SUM(o.amount) FROM u LEFT JOIN o ON o.uid = u.id GROUP BY u.name ORDER BY u.name"));
    }

    @Test
    void threeWayJoin() {
        db.execute("CREATE TABLE c (uid INT, tag TEXT)");
        db.execute("INSERT INTO c VALUES (1, 'vip'), (3, 'new')");
        assertEquals(List.of(r("vip", 10L), r("vip", 11L), r("new", 12L)),
                rows("SELECT c.tag, o.id FROM u JOIN o ON o.uid = u.id JOIN c ON c.uid = u.id"));
    }

    @Test
    void rollbackRestoresRowsAndDdl() {
        db.execute("BEGIN");
        db.execute("INSERT INTO u VALUES (4, 'dee')");
        db.execute("DELETE FROM o");
        db.execute("DROP TABLE u");
        db.execute("CREATE TABLE tmp (a INT)");
        db.execute("ROLLBACK");
        assertEquals(List.of(r(1L), r(2L), r(3L)), rows("SELECT id FROM u"));
        assertEquals(5, rows("SELECT * FROM o").size());
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM tmp"));
    }

    @Test
    void commitKeepsChangesAndEndsTheTransaction() {
        db.execute("BEGIN");
        db.execute("UPDATE u SET name = 'ANN' WHERE id = 1");
        db.execute("COMMIT");
        assertEquals(List.of(r("ANN")), rows("SELECT name FROM u WHERE id = 1"));
        assertThrows(SqlException.class, () -> db.execute("COMMIT"));
        assertThrows(SqlException.class, () -> db.execute("ROLLBACK"));
    }

    @Test
    void nestedBeginIsRejectedAndTransactionSurvivesIt() {
        db.execute("BEGIN");
        db.execute("INSERT INTO u VALUES (4, 'dee')");
        assertThrows(SqlException.class, () -> db.execute("BEGIN"));
        db.execute("ROLLBACK");
        assertEquals(3, rows("SELECT * FROM u").size());
    }

    @Test
    void failedStatementInsideTransactionDoesNotAbortIt() {
        db.execute("BEGIN");
        db.execute("INSERT INTO u VALUES (4, 'dee')");
        assertThrows(SqlException.class, () -> db.execute("INSERT INTO u VALUES (4, 'dup')"));
        db.execute("COMMIT");
        assertEquals(List.of(r("dee")), rows("SELECT name FROM u WHERE id = 4"));
    }

    @Test
    void rollbackThenChangesAreIndependentOfTheDiscardedSnapshot() {
        db.execute("BEGIN");
        db.execute("UPDATE u SET name = 'x'");
        db.execute("ROLLBACK");
        db.execute("BEGIN");
        db.execute("UPDATE u SET name = 'y' WHERE id = 1");
        db.execute("ROLLBACK");
        assertEquals(List.of(r("ann"), r("bob"), r("cy")), rows("SELECT name FROM u ORDER BY id"));
    }
}
