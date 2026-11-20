package ae.lp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** An LP over variables that are all implicitly {@code >= 0}. Immutable. */
public final class LpModel {
    public enum Sense { MAX, MIN }

    public enum Op {
        LE("<="), GE(">="), EQ("=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** One row {@code coef . x op rhs}; the coefficient array is copied on the way in and out. */
    public record Constraint(String name, Rational[] coef, Op op, Rational rhs) {
        public Constraint {
            coef = coef.clone();
        }

        @Override
        public Rational[] coef() {
            return coef.clone();
        }
    }

    private final Sense sense;
    private final List<String> variables;
    private final Rational[] objective;
    private final List<Constraint> constraints;

    public LpModel(Sense sense, List<String> variables, Rational[] objective, List<Constraint> constraints) {
        if (objective.length != variables.size()) {
            throw new IllegalArgumentException("objective length != variable count");
        }
        for (Constraint c : constraints) {
            if (c.coef().length != variables.size()) {
                throw new IllegalArgumentException("constraint " + c.name() + " has wrong length");
            }
        }
        this.sense = sense;
        this.variables = List.copyOf(variables);
        this.objective = objective.clone();
        this.constraints = List.copyOf(new ArrayList<>(constraints));
    }

    public Sense sense() {
        return sense;
    }

    public List<String> variables() {
        return variables;
    }

    public Rational[] objective() {
        return objective.clone();
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    @Override
    public String toString() {
        String rows = constraints.stream()
                .map(c -> c.name() + ": " + Arrays.toString(c.coef()) + " " + c.op().symbol() + " " + c.rhs())
                .collect(Collectors.joining("; "));
        return sense + " " + Arrays.toString(objective) + " s.t. " + rows;
    }
}
