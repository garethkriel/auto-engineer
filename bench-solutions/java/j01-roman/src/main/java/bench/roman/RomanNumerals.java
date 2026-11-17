package bench.roman;

/** Canonical Roman numeral conversion for 1-3999. */
public final class RomanNumerals {
    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private RomanNumerals() { }

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
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("empty numeral");
        }
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = value(s.charAt(i));
            int next = i + 1 < s.length() ? value(s.charAt(i + 1)) : 0;
            total += v < next ? -v : v;
        }
        // Canonical check: the numeral must be exactly what toRoman produces.
        if (total < 1 || total > 3999 || !toRoman(total).equals(s)) {
            throw new IllegalArgumentException("not canonical: " + s);
        }
        return total;
    }

    private static int value(char c) {
        switch (c) {
            case 'I': return 1;
            case 'V': return 5;
            case 'X': return 10;
            case 'L': return 50;
            case 'C': return 100;
            case 'D': return 500;
            case 'M': return 1000;
            default: throw new IllegalArgumentException("bad character: " + c);
        }
    }
}
