package ae;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable plain-text table formatter with alignment and stable sorting. */
public final class TextTable {
    /** Cell alignment within a column. */
    public enum Align { LEFT, RIGHT }

    private final List<String> headers;
    private final List<Align> aligns;
    private final List<List<String>> rows;

    private TextTable(List<String> headers, List<Align> aligns, List<List<String>> rows) {
        this.headers = headers;
        this.aligns = aligns;
        this.rows = rows;
    }

    /** Creates an empty table. */
    public static TextTable of(List<String> headers, List<Align> aligns) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(aligns, "aligns");
        if (headers.isEmpty() || headers.size() != aligns.size()) {
            throw new IllegalArgumentException("headers must be non-empty and match aligns");
        }
        List<String> h = new ArrayList<>();
        for (String s : headers) {
            h.add(s == null ? "" : s);
        }
        return new TextTable(List.copyOf(h), List.copyOf(aligns), List.of());
    }

    /** Returns a new table with the row appended. */
    public TextTable withRow(String... cells) {
        if (cells == null || cells.length != headers.size()) {
            throw new IllegalArgumentException("expected " + headers.size() + " cells");
        }
        List<String> row = new ArrayList<>(cells.length);
        for (String c : cells) {
            row.add(c == null ? "" : c);
        }
        List<List<String>> next = new ArrayList<>(rows);
        next.add(List.copyOf(row));
        return new TextTable(headers, aligns, List.copyOf(next));
    }

    public int rowCount() {
        return rows.size();
    }

    /** Returns a new table stably sorted by the given column. */
    public TextTable sortedBy(int column, boolean numeric, boolean descending) {
        if (column < 0 || column >= headers.size()) {
            throw new IllegalArgumentException("bad column " + column);
        }
        Comparator<List<String>> cmp;
        if (numeric) {
            Comparator<Double> order = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
            cmp = (a, b) -> {
                OptionalDouble x = parse(a.get(column));
                OptionalDouble y = parse(b.get(column));
                if (x.isPresent() && y.isPresent()) {
                    return order.compare(x.getAsDouble(), y.getAsDouble());
                }
                if (x.isPresent()) {
                    return -1;
                }
                return y.isPresent() ? 1 : 0;
            };
        } else {
            Comparator<String> order = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
            cmp = Comparator.comparing(r -> r.get(column), order);
        }
        List<List<String>> sorted = new ArrayList<>(rows);
        sorted.sort(cmp); // List.sort is stable
        return new TextTable(headers, aligns, List.copyOf(sorted));
    }

    private static OptionalDouble parse(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** Renders the table as text. */
    public String render() {
        int n = headers.size();
        int[] width = new int[n];
        for (int i = 0; i < n; i++) {
            width[i] = headers.get(i).length();
            for (List<String> r : rows) {
                width[i] = Math.max(width[i], r.get(i).length());
            }
        }
        StringBuilder out = new StringBuilder();
        appendLine(out, headers, width);
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sep.append("-+-");
            }
            sep.append("-".repeat(width[i]));
        }
        out.append('\n').append(sep);
        for (List<String> r : rows) {
            out.append('\n');
            appendLine(out, r, width);
        }
        return out.toString();
    }

    private void appendLine(StringBuilder out, List<String> cells, int[] width) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(" | ");
            }
            String c = cells.get(i);
            String pad = " ".repeat(width[i] - c.length());
            if (aligns.get(i) == Align.RIGHT) {
                line.append(pad).append(c);
            } else {
                line.append(c).append(pad);
            }
        }
        out.append(line.toString().stripTrailing());
    }
}
