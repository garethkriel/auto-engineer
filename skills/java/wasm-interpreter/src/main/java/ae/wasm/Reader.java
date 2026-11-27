package ae.wasm;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounds-checked, strict reader over a slice of the module image. */
final class Reader {
    final byte[] data;
    int pos;
    final int end;

    Reader(byte[] data, int pos, int end) {
        this.data = data;
        this.pos = pos;
        this.end = end;
    }

    ModuleException malformed(String message) {
        return new ModuleException(ModuleException.Kind.MALFORMED, "malformed at offset " + pos + ": " + message);
    }

    static ModuleException invalid(String message) {
        return new ModuleException(ModuleException.Kind.INVALID, message);
    }

    boolean eof() {
        return pos >= end;
    }

    int remaining() {
        return end - pos;
    }

    int peek() {
        if (pos >= end) {
            throw malformed("unexpected end");
        }
        return data[pos] & 0xFF;
    }

    int u8() {
        int b = peek();
        pos++;
        return b;
    }

    /**
     * Reads a LEB128 integer of at most {@code bits} bits. The unused high bits of the final byte must be zero
     * (unsigned) or copies of the sign bit (signed); over-long encodings are rejected. Unsigned results are
     * non-negative longs; signed results are sign-extended.
     */
    long leb(int bits, boolean signed) {
        int maxBytes = (bits + 6) / 7;
        long result = 0;
        int shift = 0;
        int lastByte = 0;
        for (int i = 0; i < maxBytes; i++) {
            int b = u8();
            lastByte = b;
            long low = b & 0x7F;
            result |= low << shift;
            boolean last = i == maxBytes - 1;
            if (last) {
                if ((b & 0x80) != 0) {
                    throw malformed("integer representation too long");
                }
                int used = bits - shift; // value bits carried by this byte
                if (used < 7) {
                    int unusedMask = 0x7F & ~((1 << used) - 1);
                    int expected = 0;
                    if (signed && ((low >> (used - 1)) & 1) != 0) {
                        expected = unusedMask;
                    }
                    if ((low & unusedMask) != expected) {
                        throw malformed("integer too large");
                    }
                }
            } else if ((b & 0x80) == 0) {
                shift += 7;
                break;
            }
            shift += 7;
        }
        // shift now counts the bits read. The unused bits of the last byte were verified above, so nothing can lie
        // above bit `bits` except sign copies: extending from the last byte's sign bit is all that is needed.
        if (signed && shift < 64 && (lastByte & 0x40) != 0) {
            result |= -1L << shift;
        }
        return result;
    }

    /** Reads a u32 element count and checks it against the bytes left (every element needs at least one byte). */
    int vecCount() {
        long n = leb(32, false);
        if (n > remaining()) {
            throw malformed("count exceeds remaining bytes");
        }
        return (int) n;
    }

    /** Reads a u32 that must fit a non-negative int (indexes). */
    int index() {
        long n = leb(32, false);
        if (n > Integer.MAX_VALUE) {
            throw malformed("index too large");
        }
        return (int) n;
    }

    byte[] bytes(int n) {
        if (n > remaining()) {
            throw malformed("unexpected end");
        }
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Reads a length-prefixed, strictly valid UTF-8 name. */
    String name() {
        int len = vecCount();
        int start = pos;
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, start, len)).toString();
            pos += len;
            return s;
        } catch (CharacterCodingException e) {
            pos = start;
            throw malformed("malformed UTF-8 encoding");
        }
    }
}
