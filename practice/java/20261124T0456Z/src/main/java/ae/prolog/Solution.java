package ae.prolog;

import java.util.Collections;
import java.util.Map;

/** One answer to a query: deep copies of the query variables, immune to later backtracking. */
public final class Solution {
    private final Map<String, Term> bindings;
    private final int choicepointsLeft;

    Solution(Map<String, Term> bindings, int choicepointsLeft) {
        this.bindings = Collections.unmodifiableMap(bindings);
        this.choicepointsLeft = choicepointsLeft;
    }

    /** The value of a named query variable (an unbound {@link Var} if the query left it free). */
    public Term get(String variable) {
        Term t = bindings.get(variable);
        if (t == null) throw new IllegalArgumentException("no query variable " + variable);
        return t;
    }

    public Map<String, Term> bindings() {
        return bindings;
    }

    /** How many choicepoints remained when this answer was produced (0 means the query was determinate so far). */
    public int choicepointsLeft() {
        return choicepointsLeft;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (var en : bindings.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(en.getKey()).append(" = ").append(Terms.format(en.getValue()));
        }
        return sb.length() == 0 ? "true" : sb.toString();
    }
}
