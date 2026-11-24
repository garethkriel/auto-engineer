package ae.prolog;

/** {@code head :- body}. Facts have body {@code true}. The variables inside are never bound: callers rename first. */
public record Clause(Term head, Term body) {}
