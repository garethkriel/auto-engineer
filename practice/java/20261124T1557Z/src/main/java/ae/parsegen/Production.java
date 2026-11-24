package ae.parsegen;

import java.util.List;

/** One grammar rule alternative; {@code id} is its position in rule order, starting at 0. */
public record Production(int id, String lhs, List<String> rhs) {
    public Production {
        rhs = List.copyOf(rhs);
    }

    @Override
    public String toString() {
        return lhs + " -> " + (rhs.isEmpty() ? Grammar.EPSILON : String.join(" ", rhs));
    }
}
