package ae.lr;

import java.util.List;

/**
 * One grammar rule {@code lhs -> rhs}. Index 0 is always the augmented rule
 * {@code $accept -> Start}; user rules are numbered from 1 in text order.
 */
public record Production(int index, String lhs, List<String> rhs) {

    /** Compact constructor: defensive copy so productions are immutable. */
    public Production {
        rhs = List.copyOf(rhs);
    }

    @Override
    public String toString() {
        return lhs + " ->" + (rhs.isEmpty() ? " ε" : " " + String.join(" ", rhs));
    }
}
