package ae.peg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An immutable, statically checked PEG. The first rule is the start rule. */
public final class Grammar {
    private final Map<String, Expr> rules;
    private final Map<String, Boolean> nullable = new HashMap<>();

    private Grammar(Map<String, Expr> rules) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        analyse();
    }

    /** Parses and checks grammar text. */
    public static Grammar parse(String text) {
        return new Grammar(GrammarParser.parseRules(text));
    }

    /** Builds a grammar from ready-made rules (checked like parsed text). */
    public static Grammar of(Map<String, Expr> rules) {
        if (rules.isEmpty()) {
            throw new GrammarException("grammar has no rules");
        }
        return new Grammar(rules);
    }

    public Map<String, Expr> rules() {
        return rules;
    }

    public String start() {
        return rules.keySet().iterator().next();
    }

    /** True when the rule can succeed without consuming input. */
    public boolean isNullable(String rule) {
        Boolean b = nullable.get(rule);
        if (b == null) {
            throw new IllegalArgumentException("unknown rule " + rule);
        }
        return b;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        rules.forEach((k, v) -> sb.append(k).append(" <- ").append(Expr.render(v)).append('\n'));
        return sb.toString();
    }

    private void analyse() {
        for (var en : rules.entrySet()) {
            checkRefs(en.getKey(), en.getValue());
        }
        boolean changed = true;
        rules.keySet().forEach(r -> nullable.put(r, false));
        while (changed) {
            changed = false;
            for (var en : rules.entrySet()) {
                if (!nullable.get(en.getKey()) && nullable(en.getValue())) {
                    nullable.put(en.getKey(), true);
                    changed = true;
                }
            }
        }
        for (var en : rules.entrySet()) {
            checkLoops(en.getKey(), en.getValue());
        }
        checkLeftRecursion();
    }

    private void checkRefs(String rule, Expr e) {
        switch (e) {
            case Expr.Ref r -> {
                if (!rules.containsKey(r.name())) {
                    throw new GrammarException("undefined rule '" + r.name() + "' referenced in rule '" + rule + "'");
                }
            }
            case Expr.Seq s -> s.items().forEach(x -> checkRefs(rule, x));
            case Expr.Choice c -> c.alts().forEach(x -> checkRefs(rule, x));
            case Expr.Repeat r -> checkRefs(rule, r.body());
            case Expr.Opt o -> checkRefs(rule, o.body());
            case Expr.Pred p -> checkRefs(rule, p.body());
            default -> { }
        }
    }

    private boolean nullable(Expr e) {
        return switch (e) {
            case Expr.Lit l -> l.text().isEmpty();
            case Expr.Any a -> false;
            case Expr.CharClass c -> false;
            case Expr.Ref r -> nullable.get(r.name());
            case Expr.Seq s -> s.items().stream().allMatch(this::nullable);
            case Expr.Choice c -> c.alts().stream().anyMatch(this::nullable);
            case Expr.Repeat r -> !r.atLeastOne() || nullable(r.body());
            case Expr.Opt o -> true;
            case Expr.Pred p -> true;
        };
    }

    private void checkLoops(String rule, Expr e) {
        switch (e) {
            case Expr.Repeat r -> {
                if (nullable(r.body())) {
                    throw new GrammarException("loop body can match the empty string in rule '" + rule + "'");
                }
                checkLoops(rule, r.body());
            }
            case Expr.Seq s -> s.items().forEach(x -> checkLoops(rule, x));
            case Expr.Choice c -> c.alts().forEach(x -> checkLoops(rule, x));
            case Expr.Opt o -> checkLoops(rule, o.body());
            case Expr.Pred p -> checkLoops(rule, p.body());
            default -> { }
        }
    }

    /** Rules that may be invoked at the same input position as the start of {@code e}. */
    private void leftCalls(Expr e, Set<String> out) {
        switch (e) {
            case Expr.Ref r -> out.add(r.name());
            case Expr.Seq s -> {
                for (Expr x : s.items()) {
                    leftCalls(x, out);
                    if (!nullable(x)) {
                        break;
                    }
                }
            }
            case Expr.Choice c -> c.alts().forEach(x -> leftCalls(x, out));
            case Expr.Repeat r -> leftCalls(r.body(), out);
            case Expr.Opt o -> leftCalls(o.body(), out);
            case Expr.Pred p -> leftCalls(p.body(), out);
            default -> { }
        }
    }

    private void checkLeftRecursion() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (var en : rules.entrySet()) {
            Set<String> out = new LinkedHashSet<>();
            leftCalls(en.getValue(), out);
            graph.put(en.getKey(), out);
        }
        Map<String, Integer> state = new HashMap<>();
        for (String r : graph.keySet()) {
            dfs(r, graph, state, new ArrayDeque<>());
        }
    }

    private void dfs(String r, Map<String, Set<String>> graph, Map<String, Integer> state, Deque<String> path) {
        Integer st = state.get(r);
        if (st != null && st == 2) {
            return;
        }
        path.addLast(r);
        if (st != null) {
            List<String> cycle = new ArrayList<>(path);
            cycle = cycle.subList(cycle.indexOf(r), cycle.size());
            throw new GrammarException("left recursion: " + String.join(" -> ", cycle));
        }
        state.put(r, 1);
        for (String next : graph.get(r)) {
            dfs(next, graph, state, path);
        }
        state.put(r, 2);
        path.removeLast();
    }
}
