package bench.roman;

/** Canonical Roman numeral conversion for 1-3999. */
public final class RomanNumerals {
    private static final int[] V = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] S = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private RomanNumerals() {}

    public static String toRoman(int n) {
        if (n < 1 || n > 3999) throw new IllegalArgumentException("out of range: " + n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < V.length; i++) {
            while (n >= V[i]) { sb.append(S[i]); n -= V[i]; }
        }
        return sb.toString();
    }

    public static int fromRoman(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("invalid numeral");
        int pos = 0, total = 0;
        for (int i = 0; i < V.length; i++) {
            int count = 0;
            while (s.startsWith(S[i], pos)) {
                pos += S[i].length();
                total += V[i];
                count++;
            }
            if (count > 3) throw new IllegalArgumentException("invalid numeral");
        }
        if (pos != s.length() || total < 1 || total > 3999 || !toRoman(total).equals(s)) {
            throw new IllegalArgumentException("invalid numeral");
        }
        return total;
    }
}
