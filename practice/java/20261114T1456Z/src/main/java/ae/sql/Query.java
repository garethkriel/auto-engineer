package ae.sql;

import java.util.Collections;
import java.util.List;

/** A built statement: SQL text plus its bind parameters in placeholder order. */
public record Query(String sql, List<Object> params) {
    public Query {
        params = Collections.unmodifiableList(new java.util.ArrayList<>(params));
    }
}
