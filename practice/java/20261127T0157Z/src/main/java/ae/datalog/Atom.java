package ae.datalog;

import java.util.List;

/** A predicate applied to terms. */
public record Atom(String pred, List<Term> args) {
    public Atom {
        args = List.copyOf(args);
    }

    int arity() {
        return args.size();
    }
}
