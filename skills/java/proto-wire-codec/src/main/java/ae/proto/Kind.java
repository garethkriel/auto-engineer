package ae.proto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** Field kinds with their wire type and Java value class. */
public enum Kind {
    INT32(0), INT64(0), UINT32(0), UINT64(0), SINT32(0), SINT64(0), BOOL(0),
    FIXED32(5), FIXED64(1), SFIXED32(5), SFIXED64(1), FLOAT(5), DOUBLE(1),
    STRING(2), BYTES(2), MESSAGE(2);

    private final int wireType;

    Kind(int wireType) {
        this.wireType = wireType;
    }

    public int wireType() {
        return wireType;
    }

    /** True for kinds that may be packed when repeated. */
    public boolean packable() {
        return this != STRING && this != BYTES && this != MESSAGE;
    }

    static Optional<Kind> byKeyword(String word) {
        for (Kind k : values()) {
            if (k != MESSAGE && k.name().toLowerCase(Locale.ROOT).equals(word)) return Optional.of(k);
        }
        return Optional.empty();
    }

    /** Throws {@link IllegalArgumentException} unless {@code v} is a legal value of this kind. */
    void validate(Object v, String field) {
        boolean ok = switch (this) {
            case INT32, SINT32, SFIXED32 -> v instanceof Integer;
            case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> v instanceof Long;
            case UINT32, FIXED32 -> v instanceof Long l && l >= 0 && l <= 0xFFFFFFFFL;
            case BOOL -> v instanceof Boolean;
            case FLOAT -> v instanceof Float;
            case DOUBLE -> v instanceof Double;
            case STRING -> v instanceof String s && wellFormed(s);
            case BYTES -> v instanceof Bytes;
            case MESSAGE -> v instanceof Msg;
        };
        if (!ok) {
            throw new IllegalArgumentException("field " + field + " (" + this + ") cannot hold "
                    + v.getClass().getSimpleName() + " " + v);
        }
    }

    private static boolean wellFormed(String s) {
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(s));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String decodeUtf8(byte[] buf, int from, int len, int offsetForError) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, from, len))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DecodeException("invalid UTF-8 in string", offsetForError);
        }
    }
}
