package ae.b2t;

import java.util.Locale;
import java.util.Objects;

/** BIP173 Bech32 and BIP350 Bech32m. Data is a sequence of 5-bit values. */
public final class Bech32 {
    static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
    private static final int MAX_LEN = 90;

    private Bech32() { }

    /** Checksum constant selector. */
    public enum Variant {
        BECH32(1), BECH32M(0x2bc830a3);

        final int constant;

        Variant(int constant) { this.constant = constant; }
    }

    /** Decoded string: lower-case hrp, 5-bit data without checksum. */
    public record Decoded(String hrp, byte[] data5, Variant variant) { }

    private static int polymod(int[] values) {
        int chk = 1;
        for (int v : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) {
                    chk ^= GEN[i];
                }
            }
        }
        return chk;
    }

    private static int[] expandHrp(String hrp, int extra) {
        int n = hrp.length();
        int[] r = new int[2 * n + 1 + extra];
        for (int i = 0; i < n; i++) {
            r[i] = hrp.charAt(i) >> 5;
            r[n + 1 + i] = hrp.charAt(i) & 31;
        }
        return r;
    }

    private static void checkHrp(String hrp) {
        if (hrp.isEmpty() || hrp.length() > 83) {
            throw new IllegalArgumentException("hrp length");
        }
        for (int i = 0; i < hrp.length(); i++) {
            char c = hrp.charAt(i);
            if (c < 33 || c > 126 || (c >= 'A' && c <= 'Z')) {
                throw new IllegalArgumentException("invalid hrp character");
            }
        }
    }

    public static String encode(String hrp, byte[] data5, Variant variant) {
        Objects.requireNonNull(hrp);
        Objects.requireNonNull(data5);
        Objects.requireNonNull(variant);
        checkHrp(hrp);
        if (hrp.length() + 1 + data5.length + 6 > MAX_LEN) {
            throw new IllegalArgumentException("too long");
        }
        int[] vals = expandHrp(hrp, data5.length + 6);
        int base = 2 * hrp.length() + 1;
        for (int i = 0; i < data5.length; i++) {
            if (data5[i] < 0 || data5[i] > 31) {
                throw new IllegalArgumentException("data value out of range");
            }
            vals[base + i] = data5[i];
        }
        int mod = polymod(vals) ^ variant.constant;
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte d : data5) {
            sb.append(CHARSET.charAt(d));
        }
        for (int i = 0; i < 6; i++) {
            sb.append(CHARSET.charAt((mod >>> (5 * (5 - i))) & 31));
        }
        return sb.toString();
    }

    public static Decoded decode(String s) {
        Objects.requireNonNull(s);
        if (s.length() > MAX_LEN) {
            throw new IllegalArgumentException("too long");
        }
        boolean lower = false;
        boolean upper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 33 || c > 126) {
                throw new IllegalArgumentException("invalid character at " + i);
            }
            lower |= c >= 'a' && c <= 'z';
            upper |= c >= 'A' && c <= 'Z';
        }
        if (lower && upper) {
            throw new IllegalArgumentException("mixed case");
        }
        String t = s.toLowerCase(Locale.ROOT);
        int sep = t.lastIndexOf('1');
        if (sep < 1 || t.length() - sep - 1 < 6) {
            throw new IllegalArgumentException("bad separator position");
        }
        String hrp = t.substring(0, sep);
        checkHrp(hrp);
        int n = t.length() - sep - 1;
        byte[] all = new byte[n];
        int[] vals = expandHrp(hrp, n);
        for (int i = 0; i < n; i++) {
            int v = CHARSET.indexOf(t.charAt(sep + 1 + i));
            if (v < 0) {
                throw new IllegalArgumentException("invalid data character");
            }
            all[i] = (byte) v;
            vals[2 * hrp.length() + 1 + i] = v;
        }
        int mod = polymod(vals);
        Variant found = null;
        for (Variant v : Variant.values()) {
            if (mod == v.constant) {
                found = v;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("bad checksum");
        }
        byte[] data = new byte[n - 6];
        System.arraycopy(all, 0, data, 0, n - 6);
        return new Decoded(hrp, data, found);
    }

    /** BIP173 reference ConvertBits. */
    public static byte[] convertBits(byte[] in, int from, int to, boolean pad) {
        Objects.requireNonNull(in);
        int acc = 0;
        int bits = 0;
        int maxv = (1 << to) - 1;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte b : in) {
            int v = from == 8 ? b & 0xff : b;
            if (v < 0 || (v >> from) != 0) {
                throw new IllegalArgumentException("value out of range");
            }
            acc = (acc << from) | v;
            bits += from;
            while (bits >= to) {
                bits -= to;
                out.write((acc >> bits) & maxv);
            }
            acc &= (1 << bits) - 1;
        }
        if (pad) {
            if (bits > 0) {
                out.write((acc << (to - bits)) & maxv);
            }
        } else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("invalid padding");
        }
        return out.toByteArray();
    }
}
