package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpressionTest extends SqlTestBase {
    private Object one(String expr) {
        return rows("SELECT " + expr).get(0).get(0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "NULL = NULL|null", "1 = 1|true", "1 != 1|false", "1 <> 2|true", "2 <= 2|true", "'a' < 'b'|true",
        "NULL AND FALSE|false", "FALSE AND NULL|false", "TRUE AND NULL|null", "NULL AND TRUE|null",
        "NULL OR TRUE|true", "TRUE OR NULL|true", "FALSE OR NULL|null", "NULL OR FALSE|null",
        "NOT NULL|null", "NULL IS NULL|true", "1 IS NULL|false", "NULL IS NOT NULL|false", "1 IS NOT NULL|true",
        "1 + NULL|null", "NULL < 3|null"})
    void threeValuedLogicTable(String expr, String expected) {
        Object got = one(expr);
        assertEquals(expected, got == null ? "null" : got.toString(), expr);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "3 IN (1, 2, 3)|true", "4 IN (1, 2, 3)|false", "4 IN (1, NULL)|null", "1 IN (1, NULL)|true",
        "NULL IN (1, 2)|null", "4 NOT IN (1, 2)|true", "4 NOT IN (1, NULL)|null", "1 NOT IN (1, NULL)|false",
        "5 BETWEEN 1 AND 5|true", "6 BETWEEN 1 AND 5|false", "6 NOT BETWEEN 1 AND 5|true", "NULL BETWEEN 1 AND 5|null",
        "0 BETWEEN NULL AND 5|null", "9 BETWEEN NULL AND 5|false"})
    void inAndBetween(String expr, String expected) {
        Object got = one(expr);
        assertEquals(expected, got == null ? "null" : got.toString(), expr);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'hello' LIKE 'h%'|true", "'hello' LIKE '%llo'|true", "'hello' LIKE 'h_llo'|true", "'hello' LIKE 'h_lo'|false",
        "'hello' LIKE '%'|true", "'' LIKE '%'|true", "'' LIKE '_'|false", "'Hello' LIKE 'h%'|false",
        "'a%b' LIKE 'a%b'|true", "'abab' LIKE '%ab'|true", "'abc' LIKE '%b%c%'|true", "'abc' LIKE 'a%c%d'|false",
        "'hello' NOT LIKE 'x%'|true", "NULL LIKE 'a'|null", "'a' LIKE NULL|null"})
    void like(String expr, String expected) {
        Object got = one(expr);
        assertEquals(expected, got == null ? "null" : got.toString(), expr);
    }

    @Test
    void scalarFunctions() {
        assertEquals("ABC", one("UPPER('abC')"));
        assertEquals("abc", one("LOWER('aBC')"));
        assertEquals(3L, one("LENGTH('abc')"));
        assertEquals(5L, one("ABS(-5)"));
        assertEquals(2L, one("COALESCE(NULL, 2, 3)"));
        assertNull(one("COALESCE(NULL, NULL)"));
        assertNull(one("UPPER(NULL)"));
        assertThrows(SqlException.class, () -> db.execute("SELECT LENGTH('a', 'b')"));
        assertThrows(SqlException.class, () -> db.execute("SELECT NOSUCH(1)"));
        assertThrows(SqlException.class, () -> db.execute("SELECT UPPER(1)"));
    }

    @Test
    void arithmeticErrors() {
        assertThrows(SqlException.class, () -> db.execute("SELECT 1 / 0"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 1 % 0"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 9223372036854775807 + 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT -9223372036854775807 - 2"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 4611686018427387904 * 2"));
        assertEquals(-3L, one("-7 / 2"), "truncating division");
        assertThrows(SqlException.class, () -> db.execute("SELECT 'a' + 1"));
    }

    @Test
    void typeMismatchInComparisonIsAnError() {
        assertThrows(SqlException.class, () -> db.execute("SELECT 1 = 'a'"));
        assertThrows(SqlException.class, () -> db.execute("SELECT TRUE < 1"));
        assertEquals(true, one("FALSE < TRUE"));
        assertThrows(SqlException.class, () -> db.execute("SELECT NOT 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT 1 AND TRUE"));
    }

    @Test
    void andOrShortCircuitSkipsRightSideErrors() {
        assertEquals(false, one("FALSE AND 1 / 0 = 1"));
        assertEquals(true, one("TRUE OR 1 / 0 = 1"));
        assertThrows(SqlException.class, () -> db.execute("SELECT TRUE AND 1 / 0 = 1"));
    }

    @Test
    void whereMustBeBoolean() {
        people();
        assertThrows(SqlException.class, () -> db.execute("SELECT * FROM p WHERE age"));
    }

    @Test
    void doubleAndLongCompareNumerically() {
        people();
        assertEquals(List.of(r(true)), rows("SELECT AVG(age) > 30 FROM p WHERE id IN (1, 5)"));
        assertEquals(List.of(r(35.5)), rows("SELECT AVG(age) FROM p WHERE id IN (1, 5)"));
    }
}
