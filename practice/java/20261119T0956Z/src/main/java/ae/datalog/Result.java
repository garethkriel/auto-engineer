package ae.datalog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Outcome of running a program. */
public final class Result {
    /** Lexicographic order of tuples. */
    public static final Comparator<List<String>> TUPLE_ORDER = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    private final Map<String, Set<List<String>>> relations;
    private final List<List<Map<String, String>>> answers;
    private final int strata;
    private final long joinWork;

    Result(Map<String, Set<List<String>>> relations, List<List<Map<String, String>>> answers, int strata,
           long joinWork) {
        this.relations = relations;
        this.answers = answers;
        this.strata = strata;
        this.joinWork = joinWork;
    }

    /** All tuples of {@code pred}, sorted; empty for an unknown predicate. */
    public List<List<String>> relation(String pred) {
        return relations.getOrDefault(pred, Set.of()).stream().sorted(TUPLE_ORDER).toList();
    }

    /** Bindings of the i-th query (named variables only), sorted by the values in order of first appearance. */
    public List<Map<String, String>> answers(int i) {
        return answers.get(i);
    }

    /** Number of strata (highest stratum index + 1; 1 for a program without negation). */
    public int strata() {
        return strata;
    }

    /** Number of complete body matches found while evaluating rules, duplicates included. */
    public long joinWork() {
        return joinWork;
    }
}
