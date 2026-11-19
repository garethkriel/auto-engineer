package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest extends SqlTestBase {
    @Test
    void arithmeticPrecedenceAndParentheses() {
        assertEquals(List.of(r(7L)), rows("SELECT 1 + 2 * 3"));
        assertEquals(List.of(r(9L)), rows("SELECT (1 + 2) * 3"));
        assertEquals(List.of(r(-4L)), rows("SELECT -2 * 2"));
        assertEquals(List.of(r(1L)), rows("SELECT 10 % 3"));
        assertEquals(List.of(r(3L)), rows("SELECT 10 - 4 - 3"), "left associative");
    }

    @Test
    void booleanPrecedence() {
        // AND binds tighter than OR: TRUE OR (FALSE AND FALSE) = TRUE
        assertEquals(List.of(r(true)), rows("SELECT TRUE OR FALSE AND FALSE"));
        assertEquals(List.of(r(false)), rows("SELECT (TRUE OR FALSE) AND FALSE"));
        // NOT binds tighter than AND: (NOT FALSE) AND FALSE = FALSE
        assertEquals(List.of(r(false)), rows("SELECT NOT FALSE AND FALSE"));
        // comparison binds tighter than NOT
        assertEquals(List.of(r(true)), rows("SELECT NOT 1 > 2"));
    }

    @Test
    void stringLiteralEscapeAndConcat() {
        assertEquals(List.of(r("it's")), rows("SELECT 'it''s'"));
        assertEquals(List.of(r("ab")), rows("SELECT 'a' || 'b'"));
        assertEquals(List.of(r((Object) null)), rows("SELECT 'a' || NULL"));
    }

    @Test
    void commentsAndCaseInsensitiveKeywords() {
        assertEquals(List.of(r(2L)), rows("select -- one\n 1 + 1 -- two"));
    }

    @Test
    void labelsAreAliasColumnOrSourceText() {
        people();
        Result res = db.execute("SELECT id, p.name, age + 1, age AS years, COUNT(*) FROM p GROUP BY id, p.name, age");
        assertEquals(List.of("id", "name", "age + 1", "years", "COUNT(*)"), res.columns());
    }

    @Test
    void unterminatedStringReportsQuotePosition() {
        String sql = "SELECT 'abc";
        SqlException e = assertThrows(SqlException.class, () -> db.execute(sql));
        assertEquals(sql.indexOf('\''), e.position());
    }

    @Test
    void unexpectedTokenReportsItsPosition() {
        String sql = "SELECT 1 FROM";
        SqlException e = assertThrows(SqlException.class, () -> db.execute(sql));
        assertEquals(sql.length(), e.position(), "end of input");
        String sql2 = "SELECT 1 + * 2";
        assertEquals(sql2.indexOf('*'), assertThrows(SqlException.class, () -> db.execute(sql2)).position());
    }

    @Test
    void unknownCharacterAndTrailingGarbage() {
        String sql = "SELECT 1 @ 2";
        assertEquals(sql.indexOf('@'), assertThrows(SqlException.class, () -> db.execute(sql)).position());
        String sql2 = "SELECT 1 2";
        assertEquals(sql2.lastIndexOf('2'), assertThrows(SqlException.class, () -> db.execute(sql2)).position());
    }

    @Test
    void executeRequiresExactlyOneStatementButScriptAllowsMany() {
        assertEquals(List.of(r(1L)), rows("SELECT 1;"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 1; SELECT 2"));
        assertThrows(SqlException.class, () -> db.execute("   "));
        List<Result> out = db.executeScript("SELECT 1; SELECT 2;;");
        assertEquals(2, out.size());
        assertEquals(List.of(r(2L)), out.get(1).rows());
    }

    @Test
    void integerLiteralOverflowIsAnError() {
        assertEquals(List.of(r(Long.MAX_VALUE)), rows("SELECT 9223372036854775807"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 9223372036854775808"));
    }

    @Test
    void reservedWordCannotBeATableName() {
        assertThrows(SqlException.class, () -> db.execute("CREATE TABLE select (a INT)"));
    }
}
