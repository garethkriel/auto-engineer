package ae.lr;

/** A parser table action. */
public sealed interface Action {

    /** Shift the lookahead and go to {@code state}. */
    record Shift(int state) implements Action { }

    /** Reduce by production {@code production}. */
    record Reduce(int production) implements Action { }

    /** Accept the input. */
    record Accept() implements Action { }
}
