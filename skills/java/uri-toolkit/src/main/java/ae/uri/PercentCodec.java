package ae.uri;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Percent encoding and strict UTF-8 percent decoding. */
public final class PercentCodec {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PercentCodec() {}

    /** Encodes every code point not accepted by {@code keep} as uppercase UTF-8 %HH octets. */
    public static String encode(String s, IntPredicate keep) {
        Objects.requireNonNull(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0xD800 && cp <= 0xDFFF) throw new IllegalArgumentException("lone surrogate");
            if (cp < 128 && keep.test(cp)) {
                sb.append((char) cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
                }
            }
        }
        return sb.toString();
    }

    /** Strict decode: every '%' must start a valid triplet and the octets must be well-formed UTF-8. */
    public static String decode(String s) {
        return decode(s, false);
    }

    /** Like {@link #decode} but '+' becomes a space (application/x-www-form-urlencoded). */
    public static String decodeForm(String s) {
        return decode(s, true);
    }

    private static String decode(String s, boolean plus) {
        Objects.requireNonNull(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("truncated percent escape at " + i);
                }
                int h = Chars.hexValue(s.charAt(i + 1)), l = Chars.hexValue(s.charAt(i + 2));
                if (h < 0 || l < 0) throw new IllegalArgumentException("bad percent escape at " + i);
                out.write(h * 16 + l);
                i += 2;
            } else if (c == '+' && plus) {
                out.write(' ');
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isSurrogate(c)) {
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                        i++;
                    } else {
                        throw new IllegalArgumentException("lone surrogate");
                    }
                }
                out.writeBytes(b);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8 in percent-encoded data", e);
        }
    }
}
