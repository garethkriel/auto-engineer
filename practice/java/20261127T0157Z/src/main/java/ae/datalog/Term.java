package ae.datalog;

import java.util.Objects;

/** A variable or a constant. Constants keep their source text (integers are canonical decimal text). */
public sealed interface Term {
    /** A logic variable. */
    record Var(String name) implements Term {
        public Var {
            Objects.requireNonNull(name);
        }
    }

    /** A constant: lowercase atom or integer. */
    record Const(String text) implements Term {
        public Const {
            Objects.requireNonNull(text);
        }
    }
}
