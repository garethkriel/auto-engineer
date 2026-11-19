package ae.rsync;

import java.util.Objects;

/**
 * Adler-style weak checksum over a sliding window of unsigned bytes.
 *
 * <p>For a window x[0..n): {@code a = sum x[i]} and {@code b = sum (n - i) * x[i]}, both modulo 2^16;
 * the value is {@code a | b << 16}. {@link #roll} slides the window by one byte in O(1).
 */
final class Rolling {
    private int a;
    private int b;
    private final int length;

    private Rolling(int a, int b, int length) {
        this.a = a;
        this.b = b;
        this.length = length;
    }

    static Rolling of(byte[] data, int off, int len) {
        check(data, off, len);
        int a = 0;
        int b = 0;
        for (int i = 0; i < len; i++) {
            int x = data[off + i] & 0xFF;
            a = (a + x) & 0xFFFF;
            b = (int) ((b + (long) (len - i) * x) & 0xFFFF);
        }
        return new Rolling(a, b, len);
    }

    static int weak(byte[] data, int off, int len) {
        return of(data, off, len).value();
    }

    /** Slide by one: drop {@code out} (the oldest byte) and append {@code in}. */
    void roll(byte out, byte in) {
        int o = out & 0xFF;
        int i = in & 0xFF;
        a = (a - o + i) & 0xFFFF;
        b = (int) ((b - (long) length * o + a) & 0xFFFF);
    }

    int value() {
        return a | (b << 16);
    }

    private static void check(byte[] data, int off, int len) {
        Objects.requireNonNull(data, "data");
        if (len <= 0 || off < 0 || off > data.length - len) {
            throw new IllegalArgumentException("bad window off=" + off + " len=" + len + " data=" + data.length);
        }
    }
}
