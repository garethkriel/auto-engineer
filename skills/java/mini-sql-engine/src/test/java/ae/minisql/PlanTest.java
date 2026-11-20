package ae.minisql;

import static ae.minisql.Fx.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {
    private static String plan(Database db, Planner p, String sql) {
        db.setPlanner(p);
        db.execute(sql);
        return String.join("\n", db.lastPlan());
    }

    @Test
    void optimizedUsesHashJoinOnEquiConditionAndNaiveDoesNot() {
        Database db = company();
        String sql = "select e.name from emp e join dept d on e.dept = d.id";
        assertTrue(plan(db, Planner.OPTIMIZED, sql).contains("hash-join"));
        assertFalse(plan(db, Planner.NAIVE, sql).contains("hash-join"));
        assertTrue(plan(db, Planner.NAIVE, sql).contains("nested-loop"));
    }

    @Test
    void nonEquiJoinFallsBackToNestedLoop() {
        Database db = company();
        String p = plan(db, Planner.OPTIMIZED, "select e.name from emp e join dept d on e.dept < d.id");
        assertFalse(p.contains("hash-join"), p);
        assertTrue(p.contains("nested-loop"), p);
    }

    @Test
    void equalityWithinOneSideIsNotAJoinKey() {
        Database db = company();
        String p = plan(db, Planner.OPTIMIZED, "select e.name from emp e join dept d on e.dept = e.boss");
        assertFalse(p.contains("hash-join"), p);
    }

    @Test
    void residualPredicateKeptNextToHashKey() {
        Database db = company();
        String sql = "select e.name from emp e join dept d on e.dept = d.id and e.salary > 75 order by e.id";
        assertTrue(plan(db, Planner.OPTIMIZED, sql).contains("hash-join keys=1 residual=1"));
        bothPlanners(db, d -> assertEquals(List.of(row("ann"), row("bob")), rows(d, sql)));
    }

    @Test
    void whereConjunctsArePushedToTheirScanOnlyWhenSafe() {
        Database db = company();
        String p = plan(db, Planner.OPTIMIZED,
                "select e.name from emp e join dept d on e.dept = d.id where e.salary > 75 and d.name = 'eng' and e.id < d.id + 5");
        assertTrue(p.contains("scan emp as e filter=1"), p);
        assertTrue(p.contains("scan dept as d filter=1"), p);
        assertTrue(p.contains("final-filter=1"), p); // the two-table conjunct
        // right side of a LEFT JOIN: the WHERE predicate must NOT be pushed into the scan
        String lp = plan(db, Planner.OPTIMIZED,
                "select d.name from dept d left join emp e on e.dept = d.id where e.id is null");
        assertTrue(lp.contains("scan emp as e filter=0"), lp);
        assertTrue(lp.contains("final-filter=1"), lp);
        // ...but a base-table predicate may be
        String bp = plan(db, Planner.OPTIMIZED,
                "select d.name from dept d left join emp e on e.dept = d.id where d.id > 1");
        assertTrue(bp.contains("scan dept as d filter=1"), bp);
    }

    @Test
    void hashJoinNeverMatchesNullKeys() {
        Database db = new Database();
        db.execute("create table l (k int, v text)");
        db.execute("create table r (k int, w text)");
        db.execute("insert into l values (1,'a'),(null,'b')");
        db.execute("insert into r values (1,'x'),(null,'y')");
        bothPlanners(db, d -> {
            assertEquals(List.of(row("a", "x")), rows(d, "select v, w from l join r on l.k = r.k"));
            assertEquals(List.of(row("a", "x"), row("b", null)),
                    rows(d, "select v, w from l left join r on l.k = r.k order by v"));
        });
    }

    @Test
    void hashJoinHandlesDuplicateKeysAsAProduct() {
        Database db = new Database();
        db.execute("create table l (k int, v int)");
        db.execute("create table r (k int, w int)");
        db.execute("insert into l values (1,1),(1,2),(2,3)");
        db.execute("insert into r values (1,10),(1,20),(3,30)");
        bothPlanners(db, d -> assertEquals(
                List.of(row(1L, 10L), row(1L, 20L), row(2L, 10L), row(2L, 20L)),
                rows(d, "select v, w from l join r on l.k = r.k order by v, w")));
    }

    @Test
    void hashJoinTypeMismatchIsStillAnError() {
        Database db = company();
        for (Planner p : Planner.values()) {
            db.setPlanner(p);
            assertThrows(SqlException.class, () -> db.execute("select 1 from emp e join dept d on e.name = d.id"), p.name());
        }
    }

    @Test
    void joinKeyMayBeAnExpressionOverOneSide() {
        Database db = company();
        String sql = "select e.name, d.name from emp e join dept d on e.dept + 1 = d.id + 0 order by e.id";
        assertTrue(plan(db, Planner.OPTIMIZED, sql).contains("hash-join"));
        bothPlanners(db, d -> assertEquals(List.of(row("ann", "ops"), row("bob", "ops"), row("cy", "empty"), row("di", "empty")), rows(d, sql)));
    }
}
