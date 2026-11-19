package ae.minisql;

import java.util.ArrayList;
import java.util.List;

/** Ordered (qualifier, column) pairs describing the shape of a working row. */
record Schema(List<String> qualifiers, List<String> names) {
    static final Schema EMPTY = new Schema(List.of(), List.of());

    Schema concat(Schema other) {
        List<String> q = new ArrayList<>(qualifiers);
        q.addAll(other.qualifiers);
        List<String> n = new ArrayList<>(names);
        n.addAll(other.names);
        return new Schema(q, n);
    }

    int size() {
        return names.size();
    }

    /** Index of the column; unqualified names must match exactly one column. */
    int find(String qualifier, String name, int pos) {
        int found = -1;
        for (int k = 0; k < names.size(); k++) {
            boolean qualOk = qualifier == null || qualifier.equals(qualifiers.get(k));
            if (qualOk && names.get(k).equals(name)) {
                if (found >= 0) {
                    throw new SqlException("ambiguous column " + name, pos);
                }
                found = k;
            }
        }
        if (found < 0) {
            String shown = qualifier == null ? name : qualifier + "." + name;
            throw new SqlException("unknown column " + shown, pos);
        }
        return found;
    }
}
