package ae.qmc;

import java.util.Optional;

/**
 * A product term over up to 16 variables. Bits set in {@code mask} are "don't care" positions
 * (the variable does not appear in the term); the other bits of {@code value} give the required
 * polarity. Variable {@code i} (A, B, ...) is bit {@code n-1-i}, so A is the most significant bit.
 */
public record Implicant(int value, int mask) implements Comparable<Implicant> {

    /** Normalises {@code value} so that masked bits are always 0. */
    public Implicant {
        value &= ~mask;
    }

    /** True if the assignment {@code minterm} satisfies this term. */
    public boolean covers(int minterm) {
        return (minterm & ~mask) == value;
    }

    /** Number of literals when the function has {@code n} variables. */
    public int literals(int n) {
        return n - Integer.bitCount(mask);
    }

    /** Merge with another term of the same mask whose values differ in exactly one bit. */
    public Optional<Implicant> combine(Implicant o) {
        if (mask != o.mask) return Optional.empty();
        int diff = value ^ o.value;
        if (Integer.bitCount(diff) != 1) return Optional.empty();
        return Optional.of(new Implicant(value, mask | diff));
    }

    @Override
    public int compareTo(Implicant o) {
        int c = Integer.compare(value, o.value);
        return c != 0 ? c : Integer.compare(mask, o.mask);
    }

    /** {@code A'B}-style text; "1" for the empty product. */
    public String toString(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int bit = 1 << (n - 1 - i);
            if ((mask & bit) != 0) continue;
            sb.append((char) ('A' + i));
            if ((value & bit) == 0) sb.append('\'');
        }
        return sb.length() == 0 ? "1" : sb.toString();
    }
}
