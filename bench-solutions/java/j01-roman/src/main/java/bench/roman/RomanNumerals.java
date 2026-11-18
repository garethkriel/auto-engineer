package bench.roman;

import java.util.regex.Pattern;

/** Conversion between integers 1-3999 and canonical upper-case Roman numerals. */
public final class RomanNumerals {
    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS =
            {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
    private static final Pattern CANONICAL =
            Pattern.compile("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})");

    private RomanNumerals() {
    }

    public static String toRoman(int n) {
        if (n < 1 || n > 3999) {
            throw new IllegalArgumentException("out of range: " + n);
        }
        StringBuilder sb = new StringBuilder();
        int rest = n;
        for (int i = 0; i < VALUES.length; i++) {
            while (rest >= VALUES[i]) {
                sb.append(SYMBOLS[i]);
                rest -= VALUES[i];
            }
        }
        return sb.toString();
    }

    public static int fromRoman(String s) {
        if (s == null || s.isEmpty() || !CANONICAL.matcher(s).matches()) {
            throw new IllegalArgumentException("not a canonical roman numeral: " + s);
        }
        int total = 0;
        int pos = 0;
        for (int i = 0; i < VALUES.length; i++) {
            while (s.startsWith(SYMBOLS[i], pos)) {
                total += VALUES[i];
                pos += SYMBOLS[i].length();
            }
        }
        return total;
    }
}
