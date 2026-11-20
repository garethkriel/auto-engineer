package ae.minisql;

import static ae.minisql.Fx.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DmlTest {
    @Test
    void insertReportsAffectedAndColumnListFillsNulls() {
        Database db = company();
        Result r = db.execute("insert into emp (name, id) values ('fy', 6), ('gi', 7)");
        assertEquals(2, r.affected());
        assertEquals(List.of(row(6L, "fy", null, null, null)), rows(db, "select * from emp where id = 6"));
    }

    @Test
    void updateUsesOldRowValuesForAllAssignments() {
        Database db = company();
        // swap-like semantics: both right-hand sides see the OLD row
        assertEquals(1, db.execute("update emp set salary = boss, boss = salary where id = 2").affected());
        assertEquals(List.of(row(1L, 80L)), rows(db, "select salary, boss from emp where id = 2"));
        assertEquals(5, db.execute("update emp set salary = salary + 1").affected());
        assertEquals(List.of(row((Object) null)), rows(db, "select salary from emp where id = 5")); // NULL + 1 stays NULL
        assertEquals(List.of(row(101L)), rows(db, "select salary from emp where id = 1"));
    }

    @Test
    void updateWithNullPredicateTouchesNothing() {
        Database db = company();
        assertEquals(0, db.execute("update emp set name = 'z' where salary > 1000").affected());
        assertEquals(0, db.execute("update emp set name = 'z' where null").affected());
        assertEquals(List.of(row("ed")), rows(db, "select name from emp where id = 5"));
    }

    @Test
    void failedUpdateLeavesTableUntouched() {
        Database db = company();
        // moving ids by +1 collides with an existing key on the last row processed: nothing may change
        assertThrows(SqlException.class, () -> db.execute("update emp set id = 3 where id < 3"));
        assertEquals(List.of(row(1L), row(2L), row(3L), row(4L), row(5L)), rows(db, "select id from emp order by id"));
        assertThrows(SqlException.class, () -> db.execute("update emp set name = null where id >= 1"));
        assertEquals(List.of(row("ann")), rows(db, "select name from emp where id = 1"));
        // a division by zero on the last matching row must not leave earlier rows updated
        assertThrows(SqlException.class, () -> db.execute("update emp set salary = 100 / (id - 5)"));
        assertEquals(List.of(row(100L)), rows(db, "select salary from emp where id = 1"));
    }

    @Test
    void updatingPrimaryKeyToItselfOrPermutingIsAllowedWhenFinalStateIsUnique() {
        Database db = new Database();
        db.execute("create table k (id int primary key, v int)");
        db.execute("insert into k values (1,10),(2,20),(3,30)");
        db.execute("update k set id = id + 1"); // transient collisions are fine: only the final state counts
        assertEquals(List.of(row(2L), row(3L), row(4L)), rows(db, "select id from k order by id"));
    }

    @Test
    void failedMultiRowInsertIsAtomic() {
        Database db = company();
        assertThrows(SqlException.class, () -> db.execute("insert into dept values (10,'a'),(11,'b'),(10,'c')"));
        assertEquals(3, rows(db, "select id from dept").size());
        assertThrows(SqlException.class, () -> db.execute("insert into dept values (10,'a'),(11,5)"));
        assertEquals(3, rows(db, "select id from dept").size());
    }

    @Test
    void delete() {
        Database db = company();
        assertEquals(2, db.execute("delete from emp where dept = 2").affected());
        assertEquals(0, db.execute("delete from emp where salary > 1000").affected());
        assertEquals(3, rows(db, "select id from emp").size());
        assertEquals(3, db.execute("delete from emp").affected());
        assertEquals(List.of(row(0L)), rows(db, "select count(*) from emp"));
    }

    @Test
    void dropTable() {
        Database db = company();
        db.execute("drop table emp");
        assertThrows(SqlException.class, () -> db.execute("select * from emp"));
        db.execute("create table emp (x int)"); // name is reusable
        assertEquals(List.of(), rows(db, "select x from emp"));
    }

    @Test
    void rollbackRestoresDataAndDdl() {
        Database db = company();
        db.execute("begin");
        db.execute("delete from emp");
        db.execute("insert into dept values (9,'new')");
        db.execute("create table tmp (a int)");
        db.execute("drop table dept");
        db.execute("rollback");
        assertEquals(5, rows(db, "select id from emp").size());
        assertEquals(3, rows(db, "select id from dept").size());
        assertThrows(SqlException.class, () -> db.execute("select a from tmp"));
    }

    @Test
    void commitKeepsChangesAndRollbackAfterCommitIsRejected() {
        Database db = company();
        db.execute("begin");
        db.execute("delete from emp where id = 1");
        db.execute("commit");
        assertThrows(SqlException.class, () -> db.execute("rollback"));
        assertEquals(4, rows(db, "select id from emp").size());
    }

    @Test
    void snapshotIsDeepSoUpdatesAfterBeginAreUndone() {
        Database db = company();
        db.execute("begin");
        db.execute("update emp set name = 'changed'");
        db.execute("rollback");
        assertEquals(List.of(row("ann")), rows(db, "select name from emp where id = 1"));
        // and rows handed out earlier are not aliased with live storage
        List<List<Object>> before = rows(db, "select name from emp where id = 1");
        db.execute("update emp set name = 'zzz' where id = 1");
        assertEquals(List.of(row("ann")), before);
    }

    @Test
    void scriptRunsStatementsInOrder() {
        Database db = new Database();
        Result last = db.executeScript("create table s (a int); insert into s values (1),(2); select sum(a) from s;");
        assertEquals(List.of(row(3L)), last.rows());
        assertThrows(SqlException.class, () -> db.executeScript("select 1 from s; select zz from s"));
    }
}
