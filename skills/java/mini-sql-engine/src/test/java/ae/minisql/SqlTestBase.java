package ae.minisql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared helpers for the minisql tests. */
abstract class SqlTestBase {
    protected Database db = new Database();

    /** A row literal that tolerates nulls. */
    protected static List<Object> r(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    protected List<List<Object>> rows(String sql) {
        return db.execute(sql).rows();
    }

    protected void people() {
        db.executeScript("""
            CREATE TABLE p (id INT PRIMARY KEY, name TEXT NOT NULL, age INT, city TEXT);
            INSERT INTO p VALUES (1, 'ann', 30, 'oslo'), (2, 'bob', NULL, 'rome'),
                                 (3, 'cy', 25, 'oslo'), (4, 'dee', 30, NULL), (5, 'eve', 41, 'rome');
            """);
    }
}
