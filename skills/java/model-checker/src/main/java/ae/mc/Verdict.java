package ae.mc;

import java.util.List;

/** Outcome of a model-checking run. */
public sealed interface Verdict<S> {
    /** The property holds on all explored (= all reachable) states. */
    record Holds<S>(int states, int transitions) implements Verdict<S> {}

    /** An invariant fails; {@code trace} is a shortest path from an initial state. */
    record InvariantViolated<S>(String name, List<Step<S>> trace) implements Verdict<S> {}

    /** A non-terminal state has no moves; {@code trace} is a shortest path to it. */
    record Deadlock<S>(List<Step<S>> trace) implements Verdict<S> {}

    /**
     * "Eventually goal" fails. {@code prefix} ends at the first state of the bad loop; {@code cycle} returns to
     * that state, or is empty when the execution ends in a stuck state that stutters forever.
     */
    record LivenessViolated<S>(String name, List<Step<S>> prefix, List<Step<S>> cycle) implements Verdict<S> {}

    /** More than the allowed number of distinct states were found before a verdict. */
    record Incomplete<S>(int states) implements Verdict<S> {}
}
