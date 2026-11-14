package fn;

import java.util.List;

/** Unchecked exception carrying the accumulated validation errors. */
public final class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    /** @param errors the errors; copied defensively */
    public ValidationException(List<String> errors) {
        super("Validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /** @return unmodifiable list of errors */
    public List<String> errors() {
        return errors;
    }
}
