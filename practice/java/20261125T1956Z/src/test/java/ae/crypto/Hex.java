package ae.crypto;

import java.util.HexFormat;

final class Hex {
    private Hex() {}
    static byte[] b(String s) { return HexFormat.of().parseHex(s.replace(" ", "")); }
    static String s(byte[] a) { return HexFormat.of().formatHex(a); }
}
