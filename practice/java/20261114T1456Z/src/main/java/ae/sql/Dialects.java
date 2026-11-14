package ae.sql;

/** Built-in dialects. */
public final class Dialects {

    private Dialects() {
    }

    private static String limitOffset(int limit, int offset) {
        return offset == 0 ? "LIMIT " + limit : "LIMIT " + limit + " OFFSET " + offset;
    }

    public static final Dialect ANSI = new Dialect() {
        @Override public String quote(String id) { return "\"" + id + "\""; }
        @Override public String placeholder(int index) { return "?"; }
        @Override public String pagination(int limit, int offset) { return limitOffset(limit, offset); }
    };

    public static final Dialect MYSQL = new Dialect() {
        @Override public String quote(String id) { return "`" + id + "`"; }
        @Override public String placeholder(int index) { return "?"; }
        @Override public String pagination(int limit, int offset) { return limitOffset(limit, offset); }
    };

    public static final Dialect POSTGRES = new Dialect() {
        @Override public String quote(String id) { return "\"" + id + "\""; }
        @Override public String placeholder(int index) { return "$" + index; }
        @Override public String pagination(int limit, int offset) { return limitOffset(limit, offset); }
    };

    public static final Dialect SQLSERVER = new Dialect() {
        @Override public String quote(String id) { return "[" + id + "]"; }
        @Override public String placeholder(int index) { return "?"; }
        @Override public String pagination(int limit, int offset) {
            return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        @Override public boolean paginationRequiresOrderBy() { return true; }
    };
}
