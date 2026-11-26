package ae.schema;

import java.util.Objects;

/**
 * One validation failure.
 *
 * @param instancePointer RFC 6901 pointer to the offending instance location ("" is the root)
 * @param schemaPointer   dynamic path of the failing keyword, including {@code /$ref} hops
 * @param keyword         the keyword that failed
 * @param message         human-readable detail
 */
public record Violation(String instancePointer, String schemaPointer, String keyword, String message) {
    public Violation {
        Objects.requireNonNull(instancePointer);
        Objects.requireNonNull(schemaPointer);
        Objects.requireNonNull(keyword);
        Objects.requireNonNull(message);
    }
}
