package ae.deflate;

/** Adler-32 checksum (RFC 1950). */
public final class Adler32 {
    private static final int MOD = 65_521;
    private static final int NMAX = 5_552; // largest n so that 255*n*(n+1)/2 + (n+1)*(MOD-1) < 2^32

    private Adler32() {}

    public static long of(byte[] data) {
        return of(data, 0, data.length);
    }

    public static long of(byte[] data, int off, int len) {
        long a = 1;
        long b = 0;
        int i = off;
        int end = off + len;
        while (i < end) {
            int stop = Math.min(end, i + NMAX);
            for (; i < stop; i++) {
                a += data[i] & 0xFF;
                b += a;
            }
            a %= MOD;
            b %= MOD;
        }
        return (b << 16) | a;
    }
}
