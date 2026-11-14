package ae.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable fluent builder for parameterised SELECT statements. Every method returns a new builder.
 */
public final class SelectBuilder {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private record Condition(String column, String op, List<Object> values) {
    }

    private record Order(String column, boolean ascending) {
    }

    private final String table;
    private final List<String> columns;
    private final List<Condition> conditions;
    private final List<Order> orders;
    private final Integer limit;
    private final Integer offset;

    private SelectBuilder(String table, List<String> columns, List<Condition> conditions, List<Order> orders,
            Integer limit, Integer offset) {
        this.table = table;
        this.columns = columns;
        this.conditions = conditions;
        this.orders = orders;
        this.limit = limit;
        this.offset = offset;
    }

    /** Starts a query on {@code table} (optionally {@code schema.table}). */
    public static SelectBuilder from(String table) {
        checkName(table, false);
        return new SelectBuilder(table, List.of(), List.of(), List.of(), null, null);
    }

    public SelectBuilder columns(String... names) {
        Objects.requireNonNull(names, "names");
        List<String> copy = new ArrayList<>();
        for (String n : names) {
            checkName(n, true);
            copy.add(n);
        }
        return new SelectBuilder(table, List.copyOf(copy), conditions, orders, limit, offset);
    }

    public SelectBuilder where(String column, Op op, Object value) {
        checkName(column, false);
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(value, "value");
        return withCondition(new Condition(column, op.sql(), List.of(value)));
    }

    public SelectBuilder whereIn(String column, List<?> values) {
        checkName(column, false);
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return withCondition(new Condition(column, "IN", List.copyOf(values)));
    }

    public SelectBuilder orderBy(String column, boolean ascending) {
        checkName(column, false);
        List<Order> next = new ArrayList<>(orders);
        next.add(new Order(column, ascending));
        return new SelectBuilder(table, columns, conditions, List.copyOf(next), limit, offset);
    }

    public SelectBuilder limit(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + n);
        }
        return new SelectBuilder(table, columns, conditions, orders, n, offset);
    }

    public SelectBuilder offset(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + n);
        }
        return new SelectBuilder(table, columns, conditions, orders, limit, n);
    }

    /** Renders the statement for {@code dialect}. */
    public Query build(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        if (offset != null && limit == null) {
            throw new IllegalStateException("offset requires limit");
        }
        if (limit != null && orders.isEmpty() && dialect.paginationRequiresOrderBy()) {
            throw new IllegalStateException("this dialect requires ORDER BY for pagination");
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        if (columns.isEmpty()) {
            sql.append('*');
        } else {
            sql.append(String.join(", ", columns.stream().map(c -> c.equals("*") ? c : quote(dialect, c)).toList()));
        }
        sql.append(" FROM ").append(quote(dialect, table));
        List<Object> params = new ArrayList<>();
        String sep = " WHERE ";
        for (Condition c : conditions) {
            sql.append(sep).append(quote(dialect, c.column())).append(' ').append(c.op()).append(' ');
            if (c.op().equals("IN")) {
                sql.append('(');
                for (int i = 0; i < c.values().size(); i++) {
                    params.add(c.values().get(i));
                    sql.append(i == 0 ? "" : ", ").append(dialect.placeholder(params.size()));
                }
                sql.append(')');
            } else {
                params.add(c.values().get(0));
                sql.append(dialect.placeholder(params.size()));
            }
            sep = " AND ";
        }
        sep = " ORDER BY ";
        for (Order o : orders) {
            sql.append(sep).append(quote(dialect, o.column())).append(o.ascending() ? " ASC" : " DESC");
            sep = ", ";
        }
        if (limit != null) {
            sql.append(' ').append(dialect.pagination(limit, offset == null ? 0 : offset));
        }
        return new Query(sql.toString(), params);
    }

    private SelectBuilder withCondition(Condition c) {
        List<Condition> next = new ArrayList<>(conditions);
        next.add(c);
        return new SelectBuilder(table, columns, List.copyOf(next), orders, limit, offset);
    }

    private static String quote(Dialect d, String name) {
        return String.join(".", java.util.Arrays.stream(name.split("\\.")).map(d::quote).toList());
    }

    private static void checkName(String name, boolean allowStar) {
        Objects.requireNonNull(name, "name");
        if (allowStar && name.equals("*")) {
            return;
        }
        // split with limit -1 keeps empty parts, so "a." and "a..b" fail the pattern check
        for (String part : name.split("\\.", -1)) {
            if (!IDENT.matcher(part).matches()) {
                throw new IllegalArgumentException("invalid identifier: " + name);
            }
        }
    }
}
