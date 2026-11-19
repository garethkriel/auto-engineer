package ae.minisql;

import ae.minisql.Ast.ColDef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A table: fixed columns plus an immutable-by-convention row list. Statements never mutate the list; they
 * validate a replacement and then swap it in, which makes every statement atomic and snapshots cheap.
 */
final class Table {
    final String name;
    final List<ColDef> columns;
    List<Object[]> rows;

    Table(String name, List<ColDef> columns, List<Object[]> rows) {
        this.name = name;
        this.columns = columns;
        this.rows = rows;
    }

    Table copy() {
        return new Table(name, columns, rows);
    }

    Schema schema(String alias) {
        return new Schema(columns.stream().map(c -> alias).toList(), columns.stream().map(ColDef::name).toList());
    }

    int indexOf(String column, int pos) {
        for (int k = 0; k < columns.size(); k++) {
            if (columns.get(k).name().equals(column)) {
                return k;
            }
        }
        throw new SqlException("unknown column " + column, pos);
    }

    /** Checks types, NOT NULL and primary-key uniqueness of a complete candidate row list. */
    void validate(List<Object[]> candidate) {
        int pk = -1;
        for (int k = 0; k < columns.size(); k++) {
            if (columns.get(k).primaryKey()) {
                pk = k;
            }
        }
        Set<Object> keys = new HashSet<>();
        for (Object[] row : candidate) {
            for (int k = 0; k < columns.size(); k++) {
                ColDef col = columns.get(k);
                Object v = row[k];
                if (v == null) {
                    if (col.notNull()) {
                        throw new SqlException("column " + col.name() + " may not be NULL");
                    }
                } else if (!typeMatches(col.type(), v)) {
                    throw new SqlException("column " + col.name() + " expects " + col.type() + " but got "
                            + Eval.typeName(v));
                }
            }
            if (pk >= 0 && !keys.add(row[pk])) {
                throw new SqlException("duplicate primary key " + row[pk] + " in " + name);
            }
        }
    }

    private static boolean typeMatches(String type, Object v) {
        return switch (type) {
            case "INT" -> v instanceof Long;
            case "TEXT" -> v instanceof String;
            default -> v instanceof Boolean;
        };
    }
}
