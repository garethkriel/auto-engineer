package bench.roman;

/** Conversion between integers 1-3999 and canonical Roman numerals. */
public final class RomanNumerals {
    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS =
            {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

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
        if (s == null || s.isEmpty() || s.length() > 15) {
            throw new IllegalArgumentException("invalid numeral");
        }
        int total = 0;
        int pos = 0;
        for (int i = 0; i < VALUES.length; i++) {
            while (s.startsWith(SYMBOLS[i], pos)) {
                total += VALUES[i];
                pos += SYMBOLS[i].length();
                if (total > 3999) {
                    throw new IllegalArgumentException("too large");
                }
            }
        }
        if (pos != s.length() || total < 1 || !toRoman(total).equals(s)) {
            throw new IllegalArgumentException("invalid numeral: " + s);
        }
        return total;
    }
}
