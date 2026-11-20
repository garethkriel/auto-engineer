package ae.regex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A compiled regular expression: the canonical minimal DFA of its language plus language-level algebra. */
public final class Regex {
    /** Half-open span {@code [start, end)} of a match. */
    public record Match(int start, int end) { }

    private final String description;
    private final Dfa dfa;

    private Regex(String description, Dfa dfa) {
        this.description = description;
        this.dfa = dfa;
    }

    public static Regex compile(String pattern) {
        Nfa nfa = Nfa.build(RegexParser.parse(pattern));
        return new Regex(pattern, Dfa.fromNfa(nfa).minimize());
    }

    /** The canonical minimal DFA (exposed for tests and for callers composing automata). */
    public Dfa dfa() {
        return dfa;
    }

    public boolean matches(CharSequence input) {
        return dfa.matches(input);
    }

    /** Leftmost-longest match starting at or after {@code from}. */
    public Optional<Match> find(CharSequence text, int from) {
        if (from < 0 || from > text.length()) {
            throw new IndexOutOfBoundsException("from=" + from + " length=" + text.length());
        }
        for (int st = from; st <= text.length(); st++) {
            int end = dfa.longestMatchEnd(text, st);
            if (end >= 0) {
                return Optional.of(new Match(st, end));
            }
        }
        return Optional.empty();
    }

    public Optional<Match> find(CharSequence text) {
        return find(text, 0);
    }

    /** Successive non-overlapping matches; after an empty match the scan moves one char forward. */
    public List<Match> findAll(CharSequence text) {
        List<Match> out = new ArrayList<>();
        int pos = 0;
        while (pos <= text.length()) {
            Optional<Match> m = find(text, pos);
            if (m.isEmpty()) {
                break;
            }
            out.add(m.get());
            pos = m.get().end() > m.get().start() ? m.get().end() : m.get().end() + 1;
        }
        return out;
    }

    public boolean equivalent(Regex other) {
        return dfa.equivalent(other.dfa);
    }

    /** Shortest string matched by exactly one of the two regexes, if the languages differ. */
    public Optional<String> distinguish(Regex other) {
        return dfa.distinguish(other.dfa);
    }

    public boolean isEmptyLanguage() {
        return dfa.shortestAccepted().isEmpty();
    }

    /** A shortest accepted string, if any. */
    public Optional<String> example() {
        return dfa.shortestAccepted();
    }

    public BigInteger count(int length) {
        return dfa.count(length);
    }

    public int minimalStateCount() {
        return dfa.stateCount();
    }

    public Regex intersect(Regex o) {
        return new Regex("(" + description + ")&(" + o.description + ")", dfa.intersect(o.dfa).minimize());
    }

    public Regex union(Regex o) {
        return new Regex("(" + description + ")|(" + o.description + ")", dfa.union(o.dfa).minimize());
    }

    public Regex minus(Regex o) {
        return new Regex("(" + description + ")-(" + o.description + ")", dfa.minus(o.dfa).minimize());
    }

    public Regex complement() {
        return new Regex("~(" + description + ")", dfa.complement().minimize());
    }

    @Override
    public String toString() {
        return "Regex[" + description + "]";
    }
}
