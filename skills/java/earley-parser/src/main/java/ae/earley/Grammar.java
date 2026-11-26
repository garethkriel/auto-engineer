package ae.earley;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** An immutable context-free grammar parsed from the text format described in TASK.md. */
public final class Grammar {
    private final List<Rule> rules;
    private final List<String> nonTerminals;
    private final Map<String, List<Integer>> byLhs;
    private final Set<String> nullable;

    private Grammar(List<Rule> rules, List<String> nonTerminals) {
        this.rules = List.copyOf(rules);
        this.nonTerminals = List.copyOf(nonTerminals);
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        for (String nt : nonTerminals) {
            map.put(nt, new ArrayList<>());
        }
        for (int i = 0; i < rules.size(); i++) {
            map.get(rules.get(i).lhs()).add(i);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        this.byLhs = map;
        this.nullable = computeNullable();
    }

    public String start() {
        return rules.get(0).lhs();
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Nonterminals in order of first definition. */
    public List<String> nonTerminals() {
        return nonTerminals;
    }

    /** Indexes into {@link #rules()} of the rules for {@code lhs}, ascending. */
    public List<Integer> ruleIndexes(String lhs) {
        List<Integer> l = byLhs.get(lhs);
        if (l == null) {
            throw new IllegalArgumentException("unknown nonterminal " + lhs);
        }
        return l;
    }

    /** Sorted set of nonterminals that derive the empty string. */
    public Set<String> nullable() {
        return nullable;
    }

    private Set<String> computeNullable() {
        Set<String> result = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Rule r : rules) {
                if (!result.contains(r.lhs()) && r.rhs().stream().allMatch(
                        s -> s instanceof Symbol.NonTerminal nt && result.contains(nt.name()))) {
                    result.add(r.lhs());
                    changed = true;
                }
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    /** Parses grammar text; throws {@link GrammarException} with a line number on any error. */
    public static Grammar parse(String text) {
        Objects.requireNonNull(text, "text");
        List<Rule> rules = new ArrayList<>();
        List<String> defined = new ArrayList<>();
        Map<String, Integer> firstUse = new LinkedHashMap<>();
        String[] lines = text.split("\r?\n", -1);
        String current = null;
        for (int ln = 1; ln <= lines.length; ln++) {
            List<String> toks = tokenize(lines[ln - 1], ln);
            if (toks.isEmpty()) {
                continue;
            }
            int pos = 0;
            if (toks.get(0).equals("|")) {
                if (current == null) {
                    throw new GrammarException(ln, "'|' with no previous rule");
                }
            } else {
                String name = toks.get(0);
                if (!isName(name)) {
                    throw new GrammarException(ln, "expected a nonterminal name but found " + name);
                }
                if (toks.size() < 2 || !toks.get(1).equals("->")) {
                    throw new GrammarException(ln, "expected '->' after " + name);
                }
                current = name;
                if (!defined.contains(name)) {
                    defined.add(name);
                }
                pos = 2;
            }
            // toks[pos..] is: [|] alt ( | alt )*  where the first '|' is optional only after '->'
            List<Symbol> alt = new ArrayList<>();
            boolean leadingBar = toks.get(0).equals("|");
            int i = leadingBar ? 1 : pos;
            for (; i < toks.size(); i++) {
                String t = toks.get(i);
                if (t.equals("|")) {
                    rules.add(new Rule(current, alt));
                    alt = new ArrayList<>();
                } else if (t.equals("->")) {
                    throw new GrammarException(ln, "unexpected '->'");
                } else if (t.charAt(0) == '\'') {
                    alt.add(new Symbol.Terminal(t.codePointAt(1)));
                } else {
                    firstUse.putIfAbsent(t, ln);
                    alt.add(new Symbol.NonTerminal(t));
                }
            }
            rules.add(new Rule(current, alt));
        }
        if (rules.isEmpty()) {
            throw new GrammarException(0, "no rules");
        }
        for (Map.Entry<String, Integer> e : firstUse.entrySet()) {
            if (!defined.contains(e.getKey())) {
                throw new GrammarException(e.getValue(), "undefined nonterminal " + e.getKey());
            }
        }
        return new Grammar(rules, defined);
    }

    private static boolean isName(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) && s.charAt(0) < 128 || s.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c < 128 && Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits one line into tokens: names, {@code ->}, {@code |} and terminals (returned as
     * {@code 'x'}, a quote, the single code point, no closing quote needed by callers).
     */
    private static List<String> tokenize(String line, int ln) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else if (c == '#') {
                break;
            } else if (c == '|') {
                out.add("|");
                i++;
            } else if (c == '-' && i + 1 < n && line.charAt(i + 1) == '>') {
                out.add("->");
                i += 2;
            } else if (c == '\'') {
                i++;
                if (i >= n) {
                    throw new GrammarException(ln, "unterminated terminal");
                }
                int cp = line.codePointAt(i);
                if (cp == '\'') {
                    throw new GrammarException(ln, "empty terminal");
                }
                if (cp == '\\') {
                    if (i + 1 >= n || (line.charAt(i + 1) != '\'' && line.charAt(i + 1) != '\\')) {
                        throw new GrammarException(ln, "bad escape in terminal");
                    }
                    cp = line.charAt(i + 1);
                    i += 2;
                } else {
                    i += Character.charCount(cp);
                }
                if (i >= n || line.charAt(i) != '\'') {
                    throw new GrammarException(ln, "terminal must be exactly one code point");
                }
                i++;
                out.add("'" + new String(Character.toChars(cp)));
            } else if (c < 128 && (Character.isLetterOrDigit(c) || c == '_')) {
                int j = i;
                while (j < n && line.charAt(j) < 128
                        && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_')) {
                    j++;
                }
                out.add(line.substring(i, j));
                i = j;
            } else {
                throw new GrammarException(ln, "unexpected character '" + c + "'");
            }
        }
        return out;
    }
}
