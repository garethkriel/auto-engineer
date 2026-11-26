package ae.b2t;

import java.util.Arrays;
import java.util.Objects;

/** Bitcoin-alphabet Base58 (no checksum). */
public final class Base58 {
    static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int[] INDEX = new int[128];

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            INDEX[ALPHABET.charAt(i)] = i;
        }
    }

    private Base58() { }

    public static String encode(byte[] data) {
        Objects.requireNonNull(data);
        int zeros = 0;
        while (zeros < data.length && data[zeros] == 0) {
            zeros++;
        }
        byte[] digits = new byte[(data.length - zeros) * 138 / 100 + 1]; // log(256)/log(58) < 1.38
        int len = 0;
        for (int i = zeros; i < data.length; i++) {
            int carry = data[i] & 0xff;
            int j = 0;
            for (int k = digits.length - 1; (carry != 0 || j < len) && k >= 0; k--, j++) {
                carry += 256 * (digits[k] & 0xff);
                digits[k] = (byte) (carry % 58);
                carry /= 58;
            }
            len = j;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("1".repeat(zeros));
        int start = digits.length - len;
        for (int i = start; i < digits.length; i++) {
            sb.append(ALPHABET.charAt(digits[i]));
        }
        return sb.toString();
    }

    public static byte[] decode(String s) {
        Objects.requireNonNull(s);
        int zeros = 0;
        while (zeros < s.length() && s.charAt(zeros) == '1') {
            zeros++;
        }
        byte[] bytes = new byte[(s.length() - zeros) * 733 / 1000 + 1]; // log(58)/log(256) < 0.733
        int len = 0;
        for (int i = zeros; i < s.length(); i++) {
            char c = s.charAt(i);
            int carry = c < 128 ? INDEX[c] : -1;
            if (carry < 0) {
                throw new IllegalArgumentException("invalid Base58 character at " + i);
            }
            int j = 0;
            for (int k = bytes.length - 1; (carry != 0 || j < len) && k >= 0; k--, j++) {
                carry += 58 * (bytes[k] & 0xff);
                bytes[k] = (byte) carry;
                carry >>= 8;
            }
            len = j;
        }
        byte[] out = new byte[zeros + len];
        System.arraycopy(bytes, bytes.length - len, out, zeros, len);
        return out;
    }
}
