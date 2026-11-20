package ae.minisql;

import ae.minisql.Ast.ColDef;
import java.util.ArrayList;
import java.util.List;

/** Schema plus row storage. Rows are never handed out directly. */
final class Table {
    final String name;
    final List<ColDef> cols;
    final int pk; // index of the PRIMARY KEY column, or -1
    List<Object[]> rows = new ArrayList<>();

    Table(String name, List<ColDef> cols) {
        this.name = name;
        this.cols = List.copyOf(cols);
        int k = -1;
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).primaryKey()) k = i;
        this.pk = k;
    }

    int indexOf(String col) {
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).name().equals(col)) return i;
        return -1;
    }

    Scope scope(String alias) {
        List<Scope.Entry> es = new ArrayList<>();
        for (ColDef c : cols) es.add(new Scope.Entry(alias, c.name()));
        return new Scope(es);
    }

    Table copy() {
        Table t = new Table(name, cols);
        for (Object[] r : rows) t.rows.add(r.clone());
        return t;
    }
}
