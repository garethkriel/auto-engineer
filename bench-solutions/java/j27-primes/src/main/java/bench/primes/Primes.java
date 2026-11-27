package bench.primes;

import java.util.Arrays;

/** Prime counting (Lucy_Hedgehog), segmented sieving and nth-prime search. */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_HI = 100_000_000_000_000L;
    private static final long MAX_SPAN = 100_000_000L;
    private static final int MAX_NTH = 1_000_000_000;
    private static final int SEGMENT = 1 << 20;

    private static int[] cachedPrimes = new int[0];
    private static int cachedLimit = 1;

    private Primes() {
    }

    public static long count(long n) {
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 2) {
            return 0;
        }
        return lucy(n);
    }

    public static long countInRange(long lo, long hi) {
        if (lo < 0 || hi < lo || hi > MAX_HI || hi - lo > MAX_SPAN) {
            throw new IllegalArgumentException("range out of bounds");
        }
        if (hi < 2) {
            return 0;
        }
        long a = Math.max(lo, 2);
        int[] bp = primesUpTo(isqrt(hi));
        long total = 0;
        while (a <= hi) {
            long b = Math.min(hi, a + SEGMENT - 1);
            boolean[] comp = sieveWindow(a, b, bp);
            for (boolean c : comp) {
                if (!c) {
                    total++;
                }
            }
            a = b + 1;
        }
        return total;
    }

    public static long nth(int n) {
        if (n < 1 || n > MAX_NTH) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n <= 1_000_000) {
            long bound = n < 6 ? 15 : (long) (n * (Math.log(n) + Math.log(Math.log(n)))) + 10;
            boolean[] comp = sieveWindow(2, bound, primesUpTo(isqrt(bound)));
            int seen = 0;
            for (int i = 0; i < comp.length; i++) {
                if (!comp[i] && ++seen == n) {
                    return i + 2L;
                }
            }
            throw new IllegalStateException("bound too small");
        }
        double ln = Math.log(n);
        double lnln = Math.log(ln);
        long x = (long) (n * (ln + lnln - 1 + (lnln - 2) / ln));
        long c = lucy(x);
        final int w = 1 << 22;
        if (c >= n) {
            long need = c - n; // 0 = largest prime <= x
            long b = x;
            while (true) {
                long a = Math.max(2, b - w + 1);
                boolean[] comp = sieveWindow(a, b, primesUpTo(isqrt(b)));
                for (int i = comp.length - 1; i >= 0; i--) {
                    if (!comp[i]) {
                        if (need == 0) {
                            return a + i;
                        }
                        need--;
                    }
                }
                b = a - 1;
            }
        }
        long need = n - c; // 1 = first prime > x
        long a = x + 1;
        while (true) {
            long b = a + w - 1;
            boolean[] comp = sieveWindow(a, b, primesUpTo(isqrt(b)));
            for (int i = 0; i < comp.length; i++) {
                if (!comp[i] && --need == 0) {
                    return a + i;
                }
            }
            a = b + 1;
        }
    }

    static long isqrt(long n) {
        long r = (long) Math.sqrt((double) n);
        while (r * r > n) {
            r--;
        }
        while ((r + 1) * (r + 1) <= n) {
            r++;
        }
        return r;
    }

    /** Number of primes <= n, n >= 2, n <= 1e12 (also used for larger via same math). */
    private static long lucy(long n) {
        int r = (int) isqrt(n);
        long[] lo = new long[r + 2];
        long[] hi = new long[r + 2];
        for (int i = 1; i <= r; i++) {
            lo[i] = i - 1;
            hi[i] = n / i - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (lo[p] == lo[p - 1]) {
                continue;
            }
            long pc = lo[p - 1];
            long p2 = (long) p * p;
            if (p2 > n) {
                break;
            }
            int end = (int) Math.min(r, n / p2);
            int lim = Math.min(end, r / p);
            for (int i = 1; i <= lim; i++) {
                hi[i] -= hi[i * p] - pc;
            }
            double nd = (double) n;
            for (int i = lim + 1; i <= end; i++) {
                hi[i] -= lo[(int) (nd / ((double) i * p))] - pc;
            }
            for (int i = r; i >= p2; i--) {
                lo[i] -= lo[i / p] - pc;
            }
        }
        return hi[1];
    }

    /** Marks composites (and numbers below 2) in [lo, hi]; index i stands for lo + i. */
    private static boolean[] sieveWindow(long lo, long hi, int[] bp) {
        int len = (int) (hi - lo + 1);
        boolean[] comp = new boolean[len];
        for (int p : bp) {
            long pp = (long) p * p;
            if (pp > hi) {
                break;
            }
            long start = Math.max(pp, (lo + p - 1) / p * p);
            for (int off = (int) (start - lo); off < len; off += p) {
                comp[off] = true;
            }
        }
        for (long v = lo; v < 2 && v <= hi; v++) {
            comp[(int) (v - lo)] = true;
        }
        return comp;
    }

    private static synchronized int[] primesUpTo(long limitL) {
        int limit = (int) Math.max(limitL, 2);
        if (limit <= cachedLimit) {
            return cachedPrimes;
        }
        int lim = Math.max(limit, 1 << 16);
        boolean[] comp = new boolean[lim + 1];
        int[] out = new int[16];
        int cnt = 0;
        for (int i = 2; i <= lim; i++) {
            if (!comp[i]) {
                if (cnt == out.length) {
                    out = Arrays.copyOf(out, cnt * 2);
                }
                out[cnt++] = i;
                for (long j = (long) i * i; j <= lim; j += i) {
                    comp[(int) j] = true;
                }
            }
        }
        cachedPrimes = Arrays.copyOf(out, cnt);
        cachedLimit = lim;
        return cachedPrimes;
    }
}
