package ae.arch;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Archive member name rules shared by every format; they make path traversal unrepresentable. */
public final class Names {
    private Names() {}

    /**
     * Accepts relative, normalised, slash-separated names. A single trailing slash marks a directory.
     *
     * @throws IllegalArgumentException with the reason
     */
    public static void validate(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        if (name.charAt(0) == '/') {
            throw new IllegalArgumentException("absolute name: " + name);
        }
        if (name.length() >= 2 && name.charAt(1) == ':') {
            throw new IllegalArgumentException("drive prefix in name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                throw new IllegalArgumentException("forbidden character 0x" + Integer.toHexString(c) + " in name");
            }
        }
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("name is not valid Unicode", e);
        }
        String body = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String seg : body.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("bad path segment '" + seg + "' in name: " + name);
            }
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Strict UTF-8 decoding; malformed input is an {@link ArchiveException}. */
    static String decodeUtf8(byte[] b, int off, int len) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            throw new ArchiveException("name is not valid UTF-8", e);
        }
    }
}
