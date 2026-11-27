package ae.datalog;

import java.util.List;

/** {@code head :- body}; empty body means fact. */
public record Rule(Atom head, List<Literal> body) {
    public Rule {
        body = List.copyOf(body);
    }
}
