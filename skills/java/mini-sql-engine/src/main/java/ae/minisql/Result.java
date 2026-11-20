package ae.minisql;

import java.util.List;

/**
 * Outcome of a statement. {@code rows} hold {@code Long}, {@code String}, {@code Boolean} or {@code null}.
 * For DML {@code affected} is the number of rows inserted/updated/deleted; for SELECT it equals the row count.
 */
public record Result(List<String> columns, List<List<Object>> rows, int affected) {
    static Result count(int n) {
        return new Result(List.of(), List.of(), n);
    }
}
