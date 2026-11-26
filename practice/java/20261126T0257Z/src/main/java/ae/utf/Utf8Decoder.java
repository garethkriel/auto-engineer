package ae.utf;

import java.util.Arrays;

/**
 * Streaming UTF-8 decoder following the WHATWG state machine.
 * Valid ranges for the continuation after the lead byte (table):
 * <pre>
 *  lead        needed  first-continuation range
 *  C2..DF      1       80..BF
 *  E0          2       A0..BF   (no overlongs)
 *  E1..EC,EE,EF 2      80..BF
 *  ED          2       80..9F   (no surrogates)
 *  F0          3       90..BF   (no overlongs)
 *  F1..F3      3       80..BF
 *  F4          3       80..8F   (max U+10FFFF)
 * </pre>
 * A byte that breaks a sequence is NOT consumed by the error: it is reprocessed as a fresh byte.
 */
public final class Utf8Decoder {
    private final boolean lossy;
    private int[] out = new int[16];
    private int size;
    private int needed;
    private int lower = 0x80;
    private int upper = 0xBF;
    private int cp;
    private long consumed;
    private long seqStart;

    private Utf8Decoder(boolean lossy) {
        this.lossy = lossy;
    }

    public static Utf8Decoder lossy() {
        return new Utf8Decoder(true);
    }

    public static Utf8Decoder strict() {
        return new Utf8Decoder(false);
    }

    /** Feeds bytes; may be called with any split of the stream, including empty chunks. */
    public void feed(byte[] bytes, int off, int len) {
        if (off < 0 || len < 0 || off > bytes.length - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len);
        }
        for (int i = off; i < off + len; i++) {
            step(bytes[i] & 0xFF);
        }
    }

    public void feed(byte[] bytes) {
        feed(bytes, 0, bytes.length);
    }

    /** Ends the stream (a truncated sequence is an error) and returns all code points; the decoder is reset. */
    public int[] finish() {
        if (needed != 0) {
            needed = 0;
            lower = 0x80;
            upper = 0xBF;
            error(seqStart);
        }
        int[] result = Arrays.copyOf(out, size);
        out = new int[16];
        size = 0;
        consumed = 0;
        return result;
    }

    private void step(int b) {
        long pos = consumed++;
        if (needed == 0) {
            seqStart = pos;
            if (b <= 0x7F) {
                emit(b);
            } else if (b >= 0xC2 && b <= 0xDF) {
                needed = 1;
                cp = b & 0x1F;
            } else if (b >= 0xE0 && b <= 0xEF) {
                if (b == 0xE0) {
                    lower = 0xA0;
                } else if (b == 0xED) {
                    upper = 0x9F;
                }
                needed = 2;
                cp = b & 0x0F;
            } else if (b >= 0xF0 && b <= 0xF4) {
                if (b == 0xF0) {
                    lower = 0x90;
                } else if (b == 0xF4) {
                    upper = 0x8F;
                }
                needed = 3;
                cp = b & 0x07;
            } else {
                error(pos);
            }
            return;
        }
        if (b < lower || b > upper) {
            needed = 0;
            lower = 0x80;
            upper = 0xBF;
            error(seqStart);
            consumed--; // reprocess this byte as a fresh lead
            step(b);
            return;
        }
        lower = 0x80;
        upper = 0xBF;
        cp = (cp << 6) | (b & 0x3F);
        if (--needed == 0) {
            emit(cp);
        }
    }

    private void error(long at) {
        if (!lossy) {
            throw new CodecException("invalid UTF-8", at);
        }
        emit(0xFFFD);
    }

    private void emit(int c) {
        if (size == out.length) {
            out = Arrays.copyOf(out, size * 2);
        }
        out[size++] = c;
    }
}
