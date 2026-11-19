package ae.mc;

import java.util.List;

/** A finite-branching transition system. States must have value-based equals/hashCode. */
public interface Model<S> {
    /** Initial states (duplicates are collapsed by the checker). */
    List<S> initial();

    /** Labelled successors of {@code state}; an empty list means no move is possible. */
    List<Move<S>> moves(S state);
}
