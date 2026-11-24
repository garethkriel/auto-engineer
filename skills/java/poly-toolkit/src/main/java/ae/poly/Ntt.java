package ae.poly;

/** Iterative number-theoretic transform over {@link Mod#P} with primitive root 3. */
public final class Ntt {
    private static final long ROOT = 3;
    private static final int MAX_LOG = 23;

    private Ntt() {}

    /**
     * Transforms {@code a} in place. The forward transform evaluates the polynomial at the n-th roots of unity;
     * the inverse includes the 1/n scaling so that inverse(forward(a)) == a.
     *
     * @throws IllegalArgumentException unless the length is a power of two in 1..2^23
     */
    public static void transform(long[] a, boolean invert) {
        int n = a.length;
        if (n == 0 || (n & (n - 1)) != 0 || n > (1 << MAX_LOG)) {
            throw new IllegalArgumentException("length must be a power of two in 1..2^23: " + n);
        }
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                long t = a[i];
                a[i] = a[j];
                a[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            long w = Mod.pow(ROOT, (Mod.P - 1) / len);
            if (invert) {
                w = Mod.inv(w);
            }
            int half = len >> 1;
            long[] tw = new long[half];
            tw[0] = 1;
            for (int k = 1; k < half; k++) {
                tw[k] = Mod.mul(tw[k - 1], w);
            }
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    long u = a[i + k];
                    long v = Mod.mul(a[i + k + half], tw[k]);
                    a[i + k] = Mod.add(u, v);
                    a[i + k + half] = Mod.sub(u, v);
                }
            }
        }
        if (invert) {
            long nInv = Mod.inv(n);
            for (int i = 0; i < n; i++) {
                a[i] = Mod.mul(a[i], nInv);
            }
        }
    }
}
