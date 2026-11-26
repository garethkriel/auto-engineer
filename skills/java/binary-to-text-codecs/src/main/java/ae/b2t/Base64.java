package ae.b2t;

import java.util.Objects;

/** Strict RFC 4648 Base64 (standard and URL-safe alphabets). */
public final class Base64 {
    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private Base64() { }

    /** Encodes {@code data}; {@code pad} appends '=' up to a multiple of 4 chars. */
    public static String encode(byte[] data, boolean url, boolean pad) {
        Objects.requireNonNull(data);
        return BaseN.encode(data, 6, url ? URL : STD, pad ? 4 : 0);
    }

    /** Strict decode: padding is mandatory iff {@code requirePad}, and forbidden otherwise. */
    public static byte[] decode(String s, boolean url, boolean requirePad) {
        Objects.requireNonNull(s);
        return BaseN.decode(s, 6, url ? URL : STD, requirePad, new boolean[] {true, false, true, true}, 4);
    }
}
