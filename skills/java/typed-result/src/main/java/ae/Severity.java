package ae;

import java.util.Locale;
import java.util.Objects;

/** Ordered severity levels. */
public enum Severity {
    LOW(1), MEDIUM(2), HIGH(4), CRITICAL(8);

    private final int weight;

    Severity(int weight) { this.weight = weight; }

    public int weight() { return weight; }

    public boolean atLeast(Severity other) { return compareTo(Objects.requireNonNull(other)) >= 0; }

    /** Parses case-insensitively, ignoring surrounding whitespace. */
    public static Result<Severity, String> parse(String s) {
        Objects.requireNonNull(s, "s");
        String key = s.strip().toUpperCase(Locale.ROOT);
        for (Severity v : values()) {
            if (v.name().equals(key)) return new Result.Ok<>(v);
        }
        return new Result.Err<>("unknown severity: '" + s + "'");
    }
}
