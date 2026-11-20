package ae.peg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/** Packrat interpreter for a {@link Grammar}. Instances are stateless and thread-safe. */
public final class PegParser {
    /** Outcome of one run: tree of the matched prefix (if any), work done, farthest failure. */
    public record Result(Optional<Node> node, int evaluations, int farthest, SortedSet<String> expected) { }

    private record Entry(int end, Node node) { }

    private final Grammar grammar;
    private final boolean memoize;
    private final Map<String, Integer> index = new HashMap<>();

    public PegParser(Grammar grammar) {
        this(grammar, true);
    }

    /** {@code memoize=false} gives the plain exponential-worst-case interpreter (used as a test oracle). */
    public PegParser(Grammar grammar, boolean memoize) {
        this.grammar = grammar;
        this.memoize = memoize;
        for (String r : grammar.rules().keySet()) {
            index.put(r, index.size());
        }
    }

    public Result run(String input) {
        return new Run(input).go();
    }

    /** Longest-match-by-PEG-rules prefix of the input, from the start rule. */
    public Optional<Node> matchPrefix(String input) {
        return run(input).node();
    }

    /** Whole-input parse; throws {@link PegParseException} describing the farthest failure. */
    public Node parse(String input) {
        Result r = run(input);
        if (r.node().isPresent() && r.node().get().end() == input.length()) {
            return r.node().get();
        }
        int end = r.node().map(Node::end).orElse(0);
        int pos;
        SortedSet<String> expected;
        if (r.node().isPresent() && r.farthest() < end) {
            pos = end;
            expected = new TreeSet<>(java.util.List.of("end of input"));
        } else {
            pos = Math.max(r.farthest(), 0);
            expected = r.expected();
        }
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos; i++) {
            if (input.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        String found = pos < input.length() ? "'" + input.charAt(pos) + "'" : "end of input";
        throw new PegParseException("line " + line + ", column " + col + ": expected "
                + String.join(" or ", expected) + ", found " + found, pos, line, col, expected);
    }

    private final class Run {
        private final String in;
        private final int n;
        private final Map<Long, Entry> memo = new HashMap<>();
        private int evals;
        private int farthest = -1;
        private final TreeSet<String> expected = new TreeSet<>();
        private int silent;

        Run(String in) {
            this.in = in;
            this.n = in.length();
        }

        Result go() {
            java.util.List<Node> out = new ArrayList<>();
            int end = ref(grammar.start(), 0, out);
            return new Result(end >= 0 ? Optional.of(out.get(0)) : Optional.empty(), evals, farthest, expected);
        }

        private int fail(int pos, String what) {
            if (silent == 0) {
                if (pos > farthest) {
                    farthest = pos;
                    expected.clear();
                }
                if (pos == farthest) {
                    expected.add(what);
                }
            }
            return -1;
        }

        private int ref(String name, int pos, java.util.List<Node> out) {
            long key = (((long) index.get(name)) * (n + 1) + pos) * 2 + (silent > 0 ? 1 : 0);
            if (memoize) {
                Entry c = memo.get(key);
                if (c != null) {
                    if (c.end() >= 0) {
                        out.add(c.node());
                    }
                    return c.end();
                }
            }
            evals++;
            java.util.List<Node> kids = new ArrayList<>();
            int end = match(grammar.rules().get(name), pos, kids);
            Node node = end >= 0 ? new Node(name, pos, end, kids) : null;
            if (memoize) {
                memo.put(key, new Entry(end, node));
            }
            if (end >= 0) {
                out.add(node);
            }
            return end;
        }

        /** Returns the end position or -1; on failure {@code out} is left exactly as it was. */
        private int match(Expr e, int pos, java.util.List<Node> out) {
            switch (e) {
                case Expr.Lit l -> {
                    return in.startsWith(l.text(), pos) ? pos + l.text().length() : fail(pos, Expr.render(l));
                }
                case Expr.Any a -> {
                    return pos < n ? pos + 1 : fail(pos, "any character");
                }
                case Expr.CharClass c -> {
                    return pos < n && c.matches(in.charAt(pos)) ? pos + 1 : fail(pos, Expr.render(c));
                }
                case Expr.Ref r -> {
                    return ref(r.name(), pos, out);
                }
                case Expr.Seq s -> {
                    int mark = out.size();
                    int cur = pos;
                    for (Expr x : s.items()) {
                        cur = match(x, cur, out);
                        if (cur < 0) {
                            out.subList(mark, out.size()).clear();
                            return -1;
                        }
                    }
                    return cur;
                }
                case Expr.Choice c -> {
                    for (Expr x : c.alts()) {
                        int r = match(x, pos, out);
                        if (r >= 0) {
                            return r;
                        }
                    }
                    return -1;
                }
                case Expr.Repeat r -> {
                    int count = 0;
                    int cur = pos;
                    while (true) {
                        int nx = match(r.body(), cur, out);
                        if (nx < 0) {
                            break;
                        }
                        cur = nx;
                        count++;
                    }
                    return r.atLeastOne() && count == 0 ? -1 : cur;
                }
                case Expr.Opt o -> {
                    int r = match(o.body(), pos, out);
                    return r >= 0 ? r : pos;
                }
                case Expr.Pred p -> {
                    silent++;
                    int r = match(p.body(), pos, new ArrayList<>());
                    silent--;
                    if (p.negative()) {
                        return r < 0 ? pos : fail(pos, "not " + Expr.render(p.body()));
                    }
                    return r >= 0 ? pos : fail(pos, "&" + Expr.render(p.body()));
                }
            }
        }
    }
}
