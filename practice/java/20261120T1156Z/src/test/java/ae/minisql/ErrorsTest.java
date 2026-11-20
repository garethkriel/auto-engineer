package ae.minisql;

import static ae.minisql.Fx.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ErrorsTest {
    private static String err(Database db, String sql) {
        SqlException e = assertThrows(SqlException.class, () -> db.execute(sql), sql);
        return e.getMessage();
    }

    @Test
    void parseErrorsCarryLineAndColumn() {
        Database db = company();
        String sql = "select id\nfrom emp\nwhere id = = 3";
        int col = sql.split("\n")[2].indexOf("= 3") + 1; // second '=' on line 3
        String m = err(db, sql);
        assertTrue(m.contains("line 3, col " + col), m);
    }

    @Test
    void eachMalformedStatementIsRejectedWithItsOwnMessage() {
        Database db = company();
        assertTrue(err(db, "select from emp").contains("expression"), "missing item");
        assertTrue(err(db, "select id emp").contains("FROM"), "missing FROM");
        assertTrue(err(db, "select id from emp where").contains("expression"), "dangling where");
        assertTrue(err(db, "select id from emp limit x").contains("number"), "bad limit");
        assertTrue(err(db, "select 'abc from emp").contains("unterminated"), "unterminated string");
        assertTrue(err(db, "select id from emp #").contains("unexpected character"), "stray char");
        assertTrue(err(db, "select id from emp where id in ()").contains("expression"), "empty IN");
        assertTrue(err(db, "create table z (a blob)").contains("type"), "unknown type");
        assertTrue(err(db, "create table z (a int primary key, b int primary key)").contains("PRIMARY KEY"), "two pks");
        assertTrue(err(db, "select id from emp; select 1").contains("end of statement"), "two stmts via execute");
        assertTrue(err(db, "frobnicate").contains("statement"), "unknown statement");
        assertTrue(err(db, "select id from emp where id = 99999999999999999999").contains("range"), "huge literal");
    }

    @Test
    void staticNameErrors() {
        Database db = company();
        assertTrue(err(db, "select nope from emp").contains("unknown column"));
        assertTrue(err(db, "select x.id from emp").contains("unknown column"));
        assertTrue(err(db, "select id from emp e join dept d on e.dept = d.id").contains("ambiguous"));
        assertTrue(err(db, "select id from nosuch").contains("unknown table"));
        assertTrue(err(db, "select * from emp e join emp e on 1 = 1").contains("duplicate"));
    }

    @Test
    void staticErrorsFireEvenOnEmptyTables() {
        Database db = new Database();
        db.execute("create table a (x int)");
        db.execute("create table b (x int)");
        for (Planner p : Planner.values()) {
            db.setPlanner(p);
            assertTrue(err(db, "select x from a join b on a.x = b.x").contains("ambiguous"), p.name());
            assertTrue(err(db, "select a.x from a where zz = 1").contains("unknown column"), p.name());
        }
    }

    @Test
    void groupingErrors() {
        Database db = company();
        assertTrue(err(db, "select name, count(*) from emp group by dept").contains("GROUP BY"));
        assertTrue(err(db, "select name, count(*) from emp").contains("GROUP BY"));
        assertTrue(err(db, "select count(sum(id)) from emp").contains("nested"));
        assertTrue(err(db, "select id from emp where count(*) > 1").contains("aggregate"));
        assertTrue(err(db, "select id from emp e join dept d on count(*) = 1").contains("aggregate"));
        assertTrue(err(db, "select id from emp having id > 1").contains("GROUP BY"));
        assertTrue(err(db, "select sum(name) from emp").contains("SUM"));
        assertTrue(err(db, "select dept, count(*) from emp group by dept order by name").contains("GROUP BY"));
        // an expression made from group keys is fine
        assertEquals(2, rows(db, "select dept + 1, count(*) from emp where dept is not null group by dept").size());
    }

    @Test
    void ddlAndConstraintErrors() {
        Database db = company();
        assertTrue(err(db, "create table emp (a int)").contains("already exists"));
        assertTrue(err(db, "drop table nosuch").contains("unknown table"));
        assertTrue(err(db, "insert into emp values (1,'dup',1,1,1)").contains("duplicate key"));
        assertTrue(err(db, "insert into emp (id) values (9)").contains("NOT NULL"));
        assertTrue(err(db, "insert into emp values (9,'x')").contains("expects"));
        assertTrue(err(db, "insert into emp (id,id,name) values (9,9,'x')").contains("duplicate column"));
        assertTrue(err(db, "insert into emp values (9,5,1,1,1)").contains("type"));
        assertTrue(err(db, "insert into emp values (9,'n',id,1,1)").contains("column"));
        assertTrue(err(db, "insert into emp values (null,'n',1,1,1)").contains("NOT NULL"));
        assertTrue(err(db, "update emp set nope = 1").contains("unknown column"));
        assertTrue(err(db, "update emp set salary = 'x'").contains("type"));
    }

    @Test
    void transactionErrors() {
        Database db = company();
        assertTrue(err(db, "commit").contains("no transaction"));
        assertTrue(err(db, "rollback").contains("no transaction"));
        db.execute("begin");
        assertTrue(err(db, "begin").contains("already"));
    }
}
