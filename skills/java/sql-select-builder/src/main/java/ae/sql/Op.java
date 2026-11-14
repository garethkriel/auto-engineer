package ae.sql;

/** Comparison operators usable in a WHERE clause. */
public enum Op {
    EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="), LIKE("LIKE");

    private final String sql;

    Op(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
