package ae.dns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable absolute domain name. Labels are stored lower-case (DNS compares names case-insensitively; this
 * toolkit does not preserve case). A label is 1..63 printable ASCII characters (no space, no dot) and the
 * wire length (sum of label length + 1, plus the root byte) is at most 255.
 */
public final class Name {
    public static final int MAX_LABEL = 63;
    public static final int MAX_WIRE = 255;
    public static final Name ROOT = new Name(List.of());

    private final List<String> labels;

    private Name(List<String> labels) {
        this.labels = labels;
    }

    /** Parses {@code "www.Example.com."} (trailing dot optional); {@code "."} is the root. */
    public static Name parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.equals(".")) {
            return ROOT;
        }
        String body = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        if (body.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            if (i == body.length() || body.charAt(i) == '.') {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        return of(parts);
    }

    /** Builds a name from labels (leftmost first), validating each. */
    static Name of(List<String> rawLabels) {
        List<String> out = new ArrayList<>(rawLabels.size());
        int wire = 1;
        for (String raw : rawLabels) {
            if (raw.isEmpty() || raw.length() > MAX_LABEL) {
                throw new IllegalArgumentException("label length must be 1.." + MAX_LABEL + ": [" + raw + "]");
            }
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c <= 0x20 || c >= 0x7f || c == '.') {
                    throw new IllegalArgumentException("illegal character in label [" + raw + "]");
                }
            }
            wire += raw.length() + 1;
            out.add(raw.toLowerCase(Locale.ROOT));
        }
        if (wire > MAX_WIRE) {
            throw new IllegalArgumentException("name longer than " + MAX_WIRE + " bytes on the wire");
        }
        return out.isEmpty() ? ROOT : new Name(List.copyOf(out));
    }

    public List<String> labels() {
        return labels;
    }

    public int labelCount() {
        return labels.size();
    }

    public boolean isRoot() {
        return labels.isEmpty();
    }

    public int wireLength() {
        int w = 1;
        for (String l : labels) {
            w += l.length() + 1;
        }
        return w;
    }

    public Name parent() {
        if (isRoot()) {
            throw new IllegalStateException("the root has no parent");
        }
        return labels.size() == 1 ? ROOT : new Name(labels.subList(1, labels.size()));
    }

    public Name child(String label) {
        List<String> l = new ArrayList<>(labels.size() + 1);
        l.add(label);
        l.addAll(labels);
        return of(l);
    }

    /** True if this name equals {@code other} or lies below it (label-wise). */
    public boolean isSubdomainOf(Name other) {
        int diff = labels.size() - other.labels.size();
        return diff >= 0 && labels.subList(diff, labels.size()).equals(other.labels);
    }

    /** The ancestor with exactly {@code count} labels (count <= labelCount()). */
    public Name ancestor(int count) {
        if (count < 0 || count > labels.size()) {
            throw new IllegalArgumentException("count " + count);
        }
        return count == 0 ? ROOT : new Name(labels.subList(labels.size() - count, labels.size()));
    }

    /** Compression key: the lower-case dotted text of this name. */
    String key() {
        return String.join(".", labels);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Name n && n.labels.equals(labels);
    }

    @Override
    public int hashCode() {
        return labels.hashCode();
    }

    @Override
    public String toString() {
        return isRoot() ? "." : String.join(".", labels) + ".";
    }
}
