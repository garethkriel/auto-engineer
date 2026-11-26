package ae.hash;

/** Strict hexadecimal codec. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    /** Lower-case hex of {@code b}. */
    public static String encode(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = DIGITS[(b[i] >> 4) & 15];
            out[2 * i + 1] = DIGITS[b[i] & 15];
        }
        return new String(out);
    }

    /** Decodes upper- or lower-case hex; odd length or a non-hex character is rejected. */
    public static byte[] decode(String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("odd length hex: " + s.length());
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (nibble(s.charAt(2 * i)) << 4 | nibble(s.charAt(2 * i + 1)));
        }
        return out;
    }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("not a hex digit: " + c);
    }
}
