package ae.tar;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 helpers: malformed input is an error, never replaced. */
final class Text {
    private Text() { }

    /** Encodes {@code s}; throws {@link IllegalArgumentException} for lone surrogates. */
    static byte[] utf8(String s, String what) {
        try {
            ByteBuffer b = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(s));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(what + " is not valid Unicode");
        }
    }

    /** Decodes {@code len} bytes at {@code off}; returns null when they are not valid UTF-8. */
    static String decode(byte[] a, int off, int len) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(a, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static boolean isAscii(byte[] b) {
        for (byte x : b) {
            if (x < 0) {
                return false;
            }
        }
        return true;
    }
}
