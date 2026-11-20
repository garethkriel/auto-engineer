package bench.roman;

/** Canonical Roman numeral conversion for 1-3999. */
public final class RomanNumerals {
    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private RomanNumerals() {}

    public static String toRoman(int n) {
        if (n < 1 || n > 3999) {
            throw new IllegalArgumentException("out of range: " + n);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            while (n >= VALUES[i]) {
                sb.append(SYMBOLS[i]);
                n -= VALUES[i];
            }
        }
        return sb.toString();
    }

    public static int fromRoman(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        int total = 0;
        int i = 0;
        for (int k = 0; k < VALUES.length && i < s.length(); k++) {
            String sym = SYMBOLS[k];
            while (s.startsWith(sym, i)) {
                total += VALUES[k];
                i += sym.length();
                if (total > 3999) {
                    throw new IllegalArgumentException("too large");
                }
            }
        }
        if (i != s.length() || total < 1 || !toRoman(total).equals(s)) {
            throw new IllegalArgumentException("not canonical: " + s);
        }
        return total;
    }
}
