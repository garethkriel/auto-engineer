package ae.minisql;

import static ae.minisql.Fx.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTest {

    @Test
    void projectionFilterOrder() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row("bob", 80L), row("ann", 100L)),
                    rows(db, "select name, salary from emp where salary > 70 order by salary"));
            assertEquals(List.of("name", "pay"),
                    db.execute("SELECT name, salary AS pay FROM emp").columns());
        });
    }

    @Test
    void nullComparisonsAreNotTrue() {
        bothPlanners(company(), db -> {
            // ed has NULL salary: neither '>' nor 'NOT >' keeps it (row 4)
            assertEquals(4, rows(db, "select id from emp where salary > 0").size());
            assertEquals(0, rows(db, "select id from emp where salary > 0 and not (salary > 0) ").size());
            assertEquals(List.of(row(5L)), rows(db, "select id from emp where salary is null"));
            assertEquals(4, rows(db, "select id from emp where salary is not null").size());
            assertEquals(List.of(row(5L)), rows(db, "select id from emp where salary = null or dept is null"));
        });
    }

    @Test
    void threeValuedLogicTable() {
        Database db = company();
        // columns: salary NULL for ed. FALSE AND NULL = FALSE, TRUE OR NULL = TRUE, TRUE AND NULL = NULL
        assertEquals(List.of(row(5L)), rows(db, "select id from emp where (salary is null) and (salary > 1 or true)"));
        assertEquals(List.of(row(false)), rows(db, "select (1 = 2 and salary > 1) from emp where id = 5"));
        assertEquals(List.of(row((Object) null)), rows(db, "select (1 = 1 and salary > 1) from emp where id = 5"));
        assertEquals(List.of(row(true)), rows(db, "select (1 = 1 or salary > 1) from emp where id = 5"));
        assertEquals(List.of(row((Object) null)), rows(db, "select not (salary > 1) from emp where id = 5"));
    }

    @Test
    void arithmeticAndConcat() {
        Database db = company();
        assertEquals(List.of(row(14L, 2L, -7L, "ann!")),
                rows(db, "select 2 + 3 * 4, 17 % 5, -7, name || '!' from emp where id = 1"));
        assertEquals(List.of(row((Object) null)), rows(db, "select salary + 1 from emp where id = 5"));
        assertEquals(List.of(row(Long.MIN_VALUE)), rows(db, "select -9223372036854775808 from emp where id = 1"));
    }

    @Test
    void integerOverflowIsAnError() {
        Database db = company();
        SqlException e = assertThrows(SqlException.class,
                () -> db.execute("select 9223372036854775807 + 1 from emp"));
        assertTrue(e.getMessage().contains("overflow"), e.getMessage());
        assertThrows(SqlException.class, () -> db.execute("select 1 / 0 from emp"));
        assertThrows(SqlException.class, () -> db.execute("select 1 % 0 from emp"));
    }

    @Test
    void inAndNotInWithNulls() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row(1L), row(2L)), rows(db, "select id from emp where dept in (1) order by id"));
            // NOT IN with a NULL in the list never yields TRUE (3VL)
            assertEquals(0, rows(db, "select id from emp where id not in (1, null)").size());
            assertEquals(List.of(row(3L), row(4L)), rows(db, "select id from emp where dept not in (1, 9) order by id"));
        });
    }

    @Test
    void like() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row("ann")), rows(db, "select name from emp where name like 'a%'"));
            assertEquals(List.of(row("bob"), row("cy")), rows(db, "select name from emp where name like '_o_' or name like 'c_' order by name"));
            assertEquals(List.of(row("ann"), row("cy"), row("di"), row("ed")),
                    rows(db, "select name from emp where name not like '%o%' order by name"));
        });
    }

    @Test
    void innerJoinAndAliases() {
        bothPlanners(company(), db -> assertEquals(
                List.of(row("ann", "eng"), row("bob", "eng"), row("cy", "ops"), row("di", "ops")),
                rows(db, "select e.name, d.name from emp e join dept d on e.dept = d.id order by e.id")));
    }

    @Test
    void leftJoinKeepsUnmatchedWithNulls() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row("eng", 2L), row("ops", 2L), row("empty", 0L)),
                    rows(db, "select d.name, count(e.id) from dept d left join emp e on e.dept = d.id "
                            + "group by d.name, d.id order by count(e.id) desc, d.id"));
            assertEquals(List.of(row("empty")),
                    rows(db, "select d.name from dept d left join emp e on e.dept = d.id where e.id is null"));
            // a LEFT JOIN ON predicate on the right side must not drop left rows
            assertEquals(3, rows(db, "select d.id from dept d left join emp e on e.dept = d.id and e.salary > 1000 order by d.id").size());
        });
    }

    @Test
    void selfJoinAndMultiJoin() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row("bob", "ann"), row("cy", "ann"), row("di", "cy"), row("ed", "cy")),
                    rows(db, "select e.name, b.name from emp e join emp b on e.boss = b.id order by e.id"));
            assertEquals(List.of(row("di", "cy", "ops")),
                    rows(db, "select e.name, b.name, d.name from emp e join emp b on e.boss = b.id "
                            + "join dept d on b.dept = d.id where e.id = 4"));
        });
    }

    @Test
    void starExpansion() {
        bothPlanners(company(), db -> {
            Result r = db.execute("select * from dept d join emp e on d.id = e.dept where e.id = 1");
            assertEquals(List.of("id", "name", "id", "name", "dept", "salary", "boss"), r.columns());
            assertEquals(List.of(row(1L, "eng", 1L, "ann", 1L, 100L, null)), r.rows());
            assertEquals(List.of("id", "name"), db.execute("select d.* from dept d").columns());
        });
    }

    @Test
    void groupByAggregates() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row((Object) null, 1L, null), row(1L, 2L, 180L), row(2L, 2L, 140L)),
                    rows(db, "select dept, count(*), sum(salary) from emp group by dept order by dept"));
            assertEquals(List.of(row(1L, 100L, 80L)),
                    rows(db, "select dept, max(salary), min(salary) from emp group by dept having sum(salary) > 150"));
            assertEquals(List.of(row(5L, 4L, 3L, 320L)),
                    rows(db, "select count(*), count(salary), count(distinct salary), sum(salary) from emp"));
            assertEquals(List.of(row("ann"), row("cy")),
                    rows(db, "select min(name) from emp where dept is not null group by dept order by 1"));
        });
    }

    @Test
    void aggregateOverNoRowsGivesOneGroup() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row(0L, null, null)), rows(db, "select count(*), sum(salary), max(name) from emp where id > 99"));
            assertEquals(List.of(), rows(db, "select dept, count(*) from emp where id > 99 group by dept"));
        });
    }

    @Test
    void groupByExpressionAndExpressionOverGroup() {
        bothPlanners(company(), db -> assertEquals(
                List.of(row(0L, 2L), row(1L, 3L)),
                rows(db, "select id % 2, count(*) from emp group by id % 2 order by id % 2")));
        bothPlanners(company(), db -> assertEquals(
                List.of(row(181L)),
                rows(db, "select sum(salary) + 1 from emp where dept = 1")));
    }

    @Test
    void distinctOrderLimitOffset() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row(70L), row(80L), row(100L)),
                    rows(db, "select distinct salary from emp where salary is not null order by salary"));
            assertEquals(List.of(row((Object) null), row(70L), row(80L), row(100L)),
                    rows(db, "select distinct salary from emp order by salary asc"));
            assertEquals(List.of(row(100L), row(80L), row(70L), row((Object) null)),
                    rows(db, "select distinct salary from emp order by salary desc"));
            assertEquals(List.of(row(2L), row(3L)), rows(db, "select id from emp order by id limit 2 offset 1"));
            assertEquals(List.of(), rows(db, "select id from emp limit 0"));
        });
    }

    @Test
    void orderByIsStableAndSupportsAliasOrdinalAndMultipleKeys() {
        bothPlanners(company(), db -> {
            assertEquals(List.of(row("cy"), row("di")),
                    rows(db, "select name from emp where salary = 70 order by salary"));
            assertEquals(List.of(row("ann", 100L), row("bob", 80L), row("cy", 70L), row("di", 70L), row("ed", null)),
                    rows(db, "select name, salary as s from emp order by s desc, 1"));
        });
    }

    @Test
    void caseInsensitiveNamesAndKeywords() {
        Database db = company();
        assertEquals(List.of(row("ann")), rows(db, "SeLeCt NAME fRoM EMP wHeRe ID = 1;"));
        assertEquals(List.of(row(1L)), rows(db, "select E.Id from Emp E where e.NAME = 'ann'"));
    }

    @Test
    void stringLiteralEscape() {
        Database db = new Database();
        db.execute("create table q (s text)");
        db.execute("insert into q values ('it''s')");
        assertEquals(List.of(row("it's")), rows(db, "select s from q"));
    }

    @Test
    void booleanColumnsAndComparisons() {
        Database db = new Database();
        db.execute("create table f (id int, ok bool)");
        db.execute("insert into f values (1, true), (2, false), (3, null)");
        assertEquals(List.of(row(1L)), rows(db, "select id from f where ok"));
        assertEquals(List.of(row(2L)), rows(db, "select id from f where not ok"));
        assertEquals(List.of(row(2L), row(1L)), rows(db, "select id from f where ok is not null order by ok, id"));
        assertEquals(List.of(row(1L), row(2L)), rows(db, "select id from f where ok is not null order by ok desc, id"));
    }

    @Test
    void typeMismatchIsAnError() {
        Database db = company();
        SqlException e = assertThrows(SqlException.class, () -> db.execute("select id from emp where name = 1"));
        assertTrue(e.getMessage().contains("type"), e.getMessage());
        assertThrows(SqlException.class, () -> db.execute("select name + 1 from emp"));
        assertThrows(SqlException.class, () -> db.execute("select id from emp where id"));
    }
}
