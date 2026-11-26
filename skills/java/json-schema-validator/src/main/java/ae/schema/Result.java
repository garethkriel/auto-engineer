package ae.schema;

import java.util.List;

/** Outcome of a validation: valid exactly when there are no violations. */
public record Result(List<Violation> violations) {
    public Result {
        violations = List.copyOf(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }
}
