package ae.mc;

/** One element of a counterexample trace: the move taken ("init" for the first) and the state reached. */
public record Step<S>(String label, S state) {}
