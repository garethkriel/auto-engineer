package ae.utf;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** One-shot UTF-8 helpers. */
public final class Utf8 {
    private Utf8() { }

    /** Encodes code points; surrogates and values above U+10FFFF are rejected with the index of the bad element. */
    public static byte[] encode(int[] cps) {
        Objects.requireNonNull(cps, "cps");
        ByteArrayOutputStream o = new ByteArrayOutputStream(cps.length);
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
                throw new CodecException("not a Unicode scalar value: " + c, i);
            }
            if (c < 0x80) {
                o.write(c);
            } else if (c < 0x800) {
                o.write(0xC0 | (c >> 6));
                o.write(0x80 | (c & 0x3F));
            } else if (c < 0x10000) {
                o.write(0xE0 | (c >> 12));
                o.write(0x80 | ((c >> 6) & 0x3F));
                o.write(0x80 | (c & 0x3F));
            } else {
                o.write(0xF0 | (c >> 18));
                o.write(0x80 | ((c >> 12) & 0x3F));
                o.write(0x80 | ((c >> 6) & 0x3F));
                o.write(0x80 | (c & 0x3F));
            }
        }
        return o.toByteArray();
    }

    /** Strict decode: the first malformed sequence throws, reporting the offset of its first byte. */
    public static int[] decodeStrict(byte[] bytes) {
        Utf8Decoder d = Utf8Decoder.strict();
        d.feed(bytes);
        return d.finish();
    }

    /** Lossy decode with one U+FFFD per maximal invalid subpart. */
    public static int[] decodeLossy(byte[] bytes) {
        Utf8Decoder d = Utf8Decoder.lossy();
        d.feed(bytes);
        return d.finish();
    }
}
