package ae.earley;

import java.util.List;

/** One production {@code lhs -> rhs}; an empty right-hand side is epsilon. */
public record Rule(String lhs, List<Symbol> rhs) {

    public Rule {
        rhs = List.copyOf(rhs);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(lhs).append(" ->");
        for (Symbol s : rhs) {
            sb.append(' ').append(s);
        }
        return sb.toString();
    }
}
