package ae.prolog;

/** A Prolog term: atom, integer, variable or compound. */
public sealed interface Term permits Atom, Int, Var, Struct {}
