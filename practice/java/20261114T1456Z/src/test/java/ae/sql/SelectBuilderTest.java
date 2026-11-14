package ae.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectBuilderTest {

    @Test
    void selectsStarByDefault() {
        Query q = SelectBuilder.from("users").build(Dialects.ANSI);
        assertEquals("SELECT * FROM \"users\"", q.sql());
        assertEquals(List.of(), q.params());
    }

    @Test
    void columnsAreQuotedPerDialect() {
        SelectBuilder b = SelectBuilder.from("users").columns("id", "name");
        assertEquals("SELECT \"id\", \"name\" FROM \"users\"", b.build(Dialects.ANSI).sql());
        assertEquals("SELECT `id`, `name` FROM `users`", b.build(Dialects.MYSQL).sql());
        assertEquals("SELECT [id], [name] FROM [users]", b.build(Dialects.SQLSERVER).sql());
    }

    @Test
    void starColumnIsNotQuoted() {
        assertEquals("SELECT * FROM \"t\"", SelectBuilder.from("t").columns("*").build(Dialects.ANSI).sql());
        assertEquals("SELECT *, \"id\" FROM \"t\"", SelectBuilder.from("t").columns("*", "id").build(Dialects.ANSI).sql());
    }

    @Test
    void whereClausesAreAndedAndParameterised() {
        Query q = SelectBuilder.from("t")
                .where("age", Op.GE, 18)
                .where("name", Op.LIKE, "a%")
                .build(Dialects.ANSI);
        assertEquals("SELECT * FROM \"t\" WHERE \"age\" >= ? AND \"name\" LIKE ?", q.sql());
        assertEquals(List.of(18, "a%"), q.params());
    }

    @Test
    void postgresUsesNumberedPlaceholders() {
        Query q = SelectBuilder.from("t")
                .where("a", Op.EQ, 1)
                .whereIn("b", List.of("x", "y"))
                .where("c", Op.NE, 3)
                .build(Dialects.POSTGRES);
        assertEquals("SELECT * FROM \"t\" WHERE \"a\" = $1 AND \"b\" IN ($2, $3) AND \"c\" <> $4", q.sql());
        assertEquals(List.of(1, "x", "y", 3), q.params());
    }

    @Test
    void valuesAreNeverInlined() {
        Query q = SelectBuilder.from("t").where("name", Op.EQ, "x'; DROP TABLE t;--").build(Dialects.ANSI);
        assertFalse(q.sql().contains("DROP"));
        assertEquals(List.of("x'; DROP TABLE t;--"), q.params());
    }

    @Test
    void orderByAndPagination() {
        Query q = SelectBuilder.from("t").orderBy("a", true).orderBy("b", false).limit(10).offset(20)
                .build(Dialects.ANSI);
        assertEquals("SELECT * FROM \"t\" ORDER BY \"a\" ASC, \"b\" DESC LIMIT 10 OFFSET 20", q.sql());
    }

    @Test
    void limitWithoutOffset() {
        assertEquals("SELECT * FROM \"t\" LIMIT 5", SelectBuilder.from("t").limit(5).build(Dialects.ANSI).sql());
    }

    @Test
    void sqlServerPagination() {
        Query q = SelectBuilder.from("t").orderBy("id", true).limit(10).offset(20).build(Dialects.SQLSERVER);
        assertEquals("SELECT * FROM [t] ORDER BY [id] ASC OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY", q.sql());
        Query noOffset = SelectBuilder.from("t").orderBy("id", true).limit(10).build(Dialects.SQLSERVER);
        assertEquals("SELECT * FROM [t] ORDER BY [id] ASC OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY", noOffset.sql());
    }

    @Test
    void sqlServerPaginationNeedsOrderBy() {
        SelectBuilder b = SelectBuilder.from("t").limit(10);
        assertThrows(IllegalStateException.class, () -> b.build(Dialects.SQLSERVER));
        assertDoesNotThrow(() -> b.build(Dialects.ANSI));
    }

    @Test
    void offsetWithoutLimitIsRejected() {
        SelectBuilder b = SelectBuilder.from("t").offset(5);
        assertThrows(IllegalStateException.class, () -> b.build(Dialects.ANSI));
    }

    @Test
    void schemaQualifiedNamesQuotePartsSeparately() {
        assertEquals("SELECT * FROM `app`.`users`", SelectBuilder.from("app.users").build(Dialects.MYSQL).sql());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a;drop", "1abc", "a\"b", "a.", ".a", "a..b", "a-b"})
    void badIdentifiersAreRejected(String id) {
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from(id));
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").columns(id));
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").where(id, Op.EQ, 1));
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").orderBy(id, true));
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> SelectBuilder.from(null));
        assertThrows(NullPointerException.class, () -> SelectBuilder.from("t").where("a", null, 1));
        assertThrows(NullPointerException.class, () -> SelectBuilder.from("t").where("a", Op.EQ, null));
        assertThrows(NullPointerException.class, () -> SelectBuilder.from("t").build(null));
    }

    @Test
    void emptyWhereInIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").whereIn("a", List.of()));
    }

    @Test
    void limitAndOffsetRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").limit(0));
        assertThrows(IllegalArgumentException.class, () -> SelectBuilder.from("t").offset(-1));
    }

    @Test
    void buildersAreImmutable() {
        SelectBuilder base = SelectBuilder.from("t");
        SelectBuilder withWhere = base.where("a", Op.EQ, 1);
        assertEquals("SELECT * FROM \"t\"", base.build(Dialects.ANSI).sql());
        assertEquals("SELECT * FROM \"t\" WHERE \"a\" = ?", withWhere.build(Dialects.ANSI).sql());
    }

    @Test
    void inputListsAreCopiedAndResultIsUnmodifiable() {
        java.util.ArrayList<Object> vals = new java.util.ArrayList<>(List.of(1, 2));
        SelectBuilder b = SelectBuilder.from("t").whereIn("a", vals);
        vals.add(3);
        Query q = b.build(Dialects.ANSI);
        assertEquals(List.of(1, 2), q.params());
        assertThrows(UnsupportedOperationException.class, () -> q.params().add(9));
    }

    @Test
    void customDialectCanBePlugged() {
        Dialect d = new Dialect() {
            public String quote(String id) { return "<" + id + ">"; }
            public String placeholder(int i) { return ":p" + i; }
            public String pagination(int limit, int offset) { return "TAKE " + limit + " SKIP " + offset; }
        };
        Query q = SelectBuilder.from("t").where("a", Op.LT, 1).limit(2).build(d);
        assertEquals("SELECT * FROM <t> WHERE <a> < :p1 TAKE 2 SKIP 0", q.sql());
    }
}
