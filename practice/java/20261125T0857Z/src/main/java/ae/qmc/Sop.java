package ae.qmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** A sum of products over {@code n} variables. Terms are kept sorted and distinct. */
public final class Sop {
    private final int n;
    private final List<Implicant> terms;

    public Sop(int n, List<Implicant> terms) {
        if (n < 1 || n > Minimizer.MAX_VARS) throw new IllegalArgumentException("n out of range: " + n);
        this.n = n;
        this.terms = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(terms)));
    }

    public int n() { return n; }

    public List<Implicant> terms() { return terms; }

    public int termCount() { return terms.size(); }

    public int literalCount(int vars) {
        int total = 0;
        for (Implicant t : terms) total += t.literals(vars);
        return total;
    }

    public boolean eval(int minterm) {
        for (Implicant t : terms) if (t.covers(minterm)) return true;
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sop s && s.n == n && s.terms.equals(terms);
    }

    @Override
    public int hashCode() {
        return 31 * n + terms.hashCode();
    }

    /** {@code 0} for no terms, otherwise terms joined with " + ". */
    @Override
    public String toString() {
        if (terms.isEmpty()) return "0";
        StringBuilder sb = new StringBuilder();
        for (Implicant t : terms) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(t.toString(n));
        }
        return sb.toString();
    }

    /**
     * Parses "0", "1" or terms such as {@code A'B + C}. A variable may appear at most once per term
     * and must be one of the first {@code n} letters (upper case).
     */
    public static Sop parse(int n, String text) {
        if (n < 1 || n > Minimizer.MAX_VARS) throw new IllegalArgumentException("n out of range: " + n);
        String s = text.trim();
        if (s.equals("0")) return new Sop(n, List.of());
        List<Implicant> terms = new ArrayList<>();
        for (String part : s.split("\\+", -1)) {
            terms.add(parseTerm(n, part.trim()));
        }
        return new Sop(n, terms);
    }

    private static Implicant parseTerm(int n, String t) {
        if (t.isEmpty()) throw new IllegalArgumentException("empty term");
        int full = (1 << n) - 1;
        if (t.equals("1")) return new Implicant(0, full);
        int mask = full, value = 0;
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i++);
            if (c < 'A' || c >= 'A' + n) throw new IllegalArgumentException("bad variable '" + c + "' in " + t);
            int bit = 1 << (n - 1 - (c - 'A'));
            if ((mask & bit) == 0) throw new IllegalArgumentException("variable repeated in " + t);
            mask &= ~bit;
            if (i < t.length() && t.charAt(i) == '\'') i++;
            else value |= bit;
        }
        return new Implicant(value, mask);
    }
}
