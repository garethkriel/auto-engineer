package ae.sql;

/** Strategy for the syntax differences between SQL databases. */
public interface Dialect {

    /** Quotes one already-validated identifier part. */
    String quote(String identifier);

    /** Bind-parameter marker; {@code index} is 1-based. */
    String placeholder(int index);

    /** Pagination clause; {@code limit >= 1}, {@code offset >= 0}. */
    String pagination(int limit, int offset);

    /** Whether pagination is only legal together with ORDER BY. */
    default boolean paginationRequiresOrderBy() {
        return false;
    }
}
