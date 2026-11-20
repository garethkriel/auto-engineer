package ae.minisql;

import java.util.Arrays;
import java.util.List;

/** Shared test helpers. */
final class Fx {
    private Fx() {}

    static List<Object> row(Object... v) {
        return Arrays.asList(v);
    }

    static List<List<Object>> rows(Database db, String sql) {
        return db.execute(sql).rows();
    }

    /** Company fixture. dept: 1 eng, 2 ops, 3 empty. emp: ids 1..5, emp 5 has NULL dept and NULL salary. */
    static Database company() {
        Database db = new Database();
        db.execute("CREATE TABLE dept (id INT PRIMARY KEY, name TEXT NOT NULL)");
        db.execute("INSERT INTO dept VALUES (1,'eng'),(2,'ops'),(3,'empty')");
        db.execute("CREATE TABLE emp (id INT PRIMARY KEY, name TEXT NOT NULL, dept INT, salary INT, boss INT)");
        db.execute("INSERT INTO emp VALUES (1,'ann',1,100,NULL),(2,'bob',1,80,1),(3,'cy',2,70,1),"
                + "(4,'di',2,70,3),(5,'ed',NULL,NULL,3)");
        return db;
    }

    static void bothPlanners(Database db, java.util.function.Consumer<Database> check) {
        for (Planner p : Planner.values()) {
            db.setPlanner(p);
            check.accept(db);
        }
    }
}
