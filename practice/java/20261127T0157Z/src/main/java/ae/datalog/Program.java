package ae.datalog;

import java.util.List;

/** Parsed source: rules (facts included) and queries in source order. */
public record Program(List<Rule> rules, List<Atom> queries) {
    public Program {
        rules = List.copyOf(rules);
        queries = List.copyOf(queries);
    }
}
