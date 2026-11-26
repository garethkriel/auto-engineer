package ae.b2t;

import java.util.Objects;

/** Strict RFC 4648 Base32 (standard and extended-hex alphabets, upper-case only). */
public final class Base32 {
    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String HEX = "0123456789ABCDEFGHIJKLMNOPQRSTUV";

    private Base32() { }

    public static String encode(byte[] data, boolean hex, boolean pad) {
        Objects.requireNonNull(data);
        return BaseN.encode(data, 5, hex ? HEX : STD, pad ? 8 : 0);
    }

    public static byte[] decode(String s, boolean hex, boolean requirePad) {
        Objects.requireNonNull(s);
        // remainder of chars mod 8 that can end a valid string: 0,2,4,5,7
        return BaseN.decode(s, 5, hex ? HEX : STD, requirePad,
            new boolean[] {true, false, true, false, true, true, false, true}, 8);
    }
}
