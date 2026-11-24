package ae.chord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** An identifier circle of {@code 2^bits} points with clockwise arithmetic and interval tests. */
public final class Ring {
    private final int bits;
    private final long size;

    /** @param bits identifier width, 1..40 */
    public Ring(int bits) {
        if (bits < 1 || bits > 40) {
            throw new IllegalArgumentException("bits must be in 1..40: " + bits);
        }
        this.bits = bits;
        this.size = 1L << bits;
    }

    public int bits() {
        return bits;
    }

    public long size() {
        return size;
    }

    /** Reduces any long onto the circle (always in {@code 0..size-1}). */
    public long mod(long x) {
        return Math.floorMod(x, size);
    }

    public long add(long a, long b) {
        return mod(a + b);
    }

    /** Clockwise distance from {@code from} to {@code to}, in {@code 0..size-1}. */
    public long dist(long from, long to) {
        return mod(to - from);
    }

    /** True if {@code x} lies strictly between a and b going clockwise; when a == b: everything but a. */
    public boolean inOpen(long x, long a, long b) {
        if (a == b) {
            return x != a;
        }
        long dx = dist(a, x);
        return dx > 0 && dx < dist(a, b);
    }

    /** True if {@code x} is in the clockwise interval (a, b]; when a == b: the whole circle. */
    public boolean inHalfOpen(long x, long a, long b) {
        if (a == b) {
            return true;
        }
        long dx = dist(a, x);
        return dx > 0 && dx <= dist(a, b);
    }

    /** Start of finger {@code i} of node {@code n}: {@code n + 2^i}. */
    public long fingerStart(long n, int i) {
        if (i < 0 || i >= bits) {
            throw new IllegalArgumentException("finger index " + i + " outside 0.." + (bits - 1));
        }
        return add(n, 1L << i);
    }

    /** The top {@code bits} bits of SHA-1 of the UTF-8 text. */
    public long hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            long top = 0;
            for (int i = 0; i < 8; i++) {
                top = (top << 8) | (d[i] & 0xffL);
            }
            return top >>> (64 - bits);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
