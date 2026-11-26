package ae.b2t;

import java.io.ByteArrayOutputStream;

/** Shared bit-group codec for the power-of-two RFC 4648 alphabets. */
final class BaseN {
    private BaseN() { }

    static String encode(byte[] data, int bits, String alphabet, int padTo) {
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        int have = 0;
        for (byte x : data) {
            acc = (acc << 8) | (x & 0xff);
            have += 8;
            while (have >= bits) {
                sb.append(alphabet.charAt((acc >> (have - bits)) & ((1 << bits) - 1)));
                have -= bits;
            }
            acc &= (1 << have) - 1;
        }
        if (have > 0) {
            sb.append(alphabet.charAt((acc << (bits - have)) & ((1 << bits) - 1)));
        }
        if (padTo > 0) {
            while (sb.length() % padTo != 0) {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    /**
     * @param validRem validRem[r] tells whether a data-char count with remainder r (mod quantum) is legal
     */
    static byte[] decode(String s, int bits, String alphabet, boolean requirePad, boolean[] validRem, int quantum) {
        int end = s.length();
        int pads = 0;
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
            pads++;
        }
        if (requirePad) {
            if (s.length() % quantum != 0) {
                throw new IllegalArgumentException("length not a multiple of " + quantum);
            }
        } else if (pads != 0) {
            throw new IllegalArgumentException("padding not allowed");
        }
        int rem = end % quantum;
        if (!validRem[rem]) {
            throw new IllegalArgumentException("invalid length");
        }
        // required pad count completes the final quantum
        int expectedPads = rem == 0 ? 0 : quantum - rem;
        if (requirePad && pads != expectedPads) {
            throw new IllegalArgumentException("wrong padding");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0;
        int have = 0;
        for (int i = 0; i < end; i++) {
            int v = alphabet.indexOf(s.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("invalid character at " + i);
            }
            acc = (acc << bits) | v;
            have += bits;
            if (have >= 8) {
                out.write((acc >> (have - 8)) & 0xff);
                have -= 8;
                acc &= (1 << have) - 1;
            }
        }
        if (acc != 0) {
            throw new IllegalArgumentException("non-zero trailing bits");
        }
        return out.toByteArray();
    }
}
