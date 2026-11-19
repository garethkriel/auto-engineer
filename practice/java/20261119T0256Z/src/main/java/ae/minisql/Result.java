package ae.minisql;

import java.util.List;

/**
 * Outcome of one statement. SELECT fills columns and rows; DML fills affected; DDL/transaction
 * statements return empty columns/rows and affected 0.
 */
public record Result(List<String> columns, List<List<Object>> rows, int affected) {
    static Result ofCount(int affected) {
        return new Result(List.of(), List.of(), affected);
    }
}
