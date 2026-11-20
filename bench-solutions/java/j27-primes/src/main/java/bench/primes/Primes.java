package bench.primes;

import java.util.Arrays;

/** Prime counting (Lucy_Hedgehog), segmented range counting and n-th prime. */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_HI = 100_000_000_000_000L;
    private static final long MAX_WINDOW = 100_000_000L;
    private static final int MAX_NTH = 1_000_000_000;

    private Primes() {
    }

    /** Number of primes p with p &lt;= n. */
    public static long count(long n) {
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        return lucy(n);
    }

    /** Number of primes p with lo &lt;= p &lt;= hi. */
    public static long countInRange(long lo, long hi) {
        if (lo < 0 || hi < lo || hi > MAX_HI || hi - lo > MAX_WINDOW) {
            throw new IllegalArgumentException("invalid range: " + lo + ".." + hi);
        }
        return sieveRange(lo, hi, null);
    }

    /** The n-th prime, nth(1) = 2. */
    public static long nth(int n) {
        if (n < 1 || n > MAX_NTH) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 200_000) {
            return nthSmall(n);
        }
        double ln = Math.log(n);
        double lln = Math.log(ln);
        double est = n * (ln + lln - 1 + (lln - 2) / ln - (lln * lln - 6 * lln + 11) / (2 * ln * ln));
        long x0 = (long) est;
        long c = lucy(x0);
        final long chunk = 1L << 22;
        if (c < n) {
            long need = n - c;
            long lo = x0 + 1;
            while (true) {
                long hi = lo + chunk - 1;
                long cnt = sieveRange(lo, hi, null);
                if (cnt >= need) {
                    long[] ps = listRange(lo, hi);
                    return ps[(int) (need - 1)];
                }
                need -= cnt;
                lo = hi + 1;
            }
        } else {
            long skip = c - n; // primes <= x0 to skip going downward
            long hi = x0;
            while (true) {
                long lo = Math.max(2, hi - chunk + 1);
                long[] ps = listRange(lo, hi);
                if (ps.length > skip) {
                    return ps[(int) (ps.length - 1 - skip)];
                }
                skip -= ps.length;
                hi = lo - 1;
            }
        }
    }

    private static long nthSmall(int n) {
        double ln = Math.log(Math.max(n, 6));
        int limit = (int) (n * (ln + Math.log(ln))) + 10;
        boolean[] comp = new boolean[limit + 1];
        int seen = 0;
        for (int i = 2; i <= limit; i++) {
            if (!comp[i]) {
                if (++seen == n) {
                    return i;
                }
                for (long j = (long) i * i; j <= limit; j += i) {
                    comp[(int) j] = true;
                }
            }
        }
        throw new IllegalStateException("bound too small");
    }

    private static long isqrt(long n) {
        long r = (long) Math.sqrt((double) n);
        while (r * r > n) {
            r--;
        }
        while ((r + 1) * (r + 1) <= n) {
            r++;
        }
        return r;
    }

    private static long lucy(long n) {
        if (n < 2) {
            return 0;
        }
        final int r = (int) isqrt(n);
        final long[] small = new long[r + 2]; // small[v] = count for v, v <= r
        final long[] large = new long[r + 2]; // large[i] = count for n / i
        final double dn = (double) n;
        for (int v = 1; v <= r; v++) {
            small[v] = v - 1L;
            large[v] = n / v - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            final long pc = small[p - 1];
            final long p2 = (long) p * p;
            final int imax = (int) Math.min(r, n / p2);
            final int split = Math.min(imax, r / p);
            for (int i = 1; i <= split; i++) {
                large[i] -= large[i * p] - pc;
            }
            for (int i = split + 1; i <= imax; i++) {
                long d = (long) i * p;
                large[i] -= small[(int) (dn / d)] - pc;
            }
            if (p2 <= r) {
                for (int v = r; v >= p2; v--) {
                    small[v] -= small[v / p] - pc;
                }
            }
        }
        return large[1];
    }

    private static int[] basePrimes(long limit) {
        int lim = (int) limit;
        boolean[] comp = new boolean[lim + 1];
        int cnt = 0;
        int[] out = new int[Math.max(16, (int) (1.3 * lim / Math.max(1, Math.log(Math.max(lim, 3)))) + 16)];
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
        return Arrays.copyOf(out, cnt);
    }

    private static long[] listRange(long lo, long hi) {
        long[][] box = new long[1][];
        sieveRange(lo, hi, box);
        return box[0];
    }

    /** Counts primes in [lo, hi]; if collect != null stores them in collect[0]. */
    private static long sieveRange(long lo, long hi, long[][] collect) {
        long[] list = collect == null ? null : new long[16];
        int listN = 0;
        long total = 0;
        if (hi >= 2 && lo <= hi) {
            long a = Math.max(lo, 2);
            if (a <= 2) {
                total++;
                if (list != null) {
                    list[listN++] = 2;
                }
            }
            long start = Math.max(a, 3);
            if ((start & 1) == 0) {
                start++;
            }
            if (start <= hi) {
                final int cntOdd = (int) ((hi - start) / 2 + 1);
                int[] bp = basePrimes(isqrt(hi));
                // odd primes only
                int np = 0;
                int[] ps = new int[bp.length];
                int[] next = new int[bp.length];
                for (int q : bp) {
                    if (q == 2) {
                        continue;
                    }
                    long sq = (long) q * q;
                    if (sq > hi) {
                        break;
                    }
                    long m = Math.max(sq, ((start + q - 1) / q) * q);
                    if ((m & 1) == 0) {
                        m += q;
                    }
                    ps[np] = q;
                    long idx = (m - start) / 2;
                    next[np] = idx > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) idx;
                    np++;
                }
                final int seg = 1 << 18;
                boolean[] comp = new boolean[seg];
                for (int base = 0; base < cntOdd; base += seg) {
                    int len = Math.min(seg, cntOdd - base);
                    Arrays.fill(comp, 0, len, false);
                    int end = base + len;
                    for (int k = 0; k < np; k++) {
                        int q = ps[k];
                        int idx = next[k];
                        while (idx < end) {
                            comp[idx - base] = true;
                            idx += q;
                        }
                        next[k] = idx;
                    }
                    for (int i = 0; i < len; i++) {
                        if (!comp[i]) {
                            total++;
                            if (list != null) {
                                if (listN == list.length) {
                                    list = Arrays.copyOf(list, listN * 2);
                                }
                                list[listN++] = start + 2L * (base + i);
                            }
                        }
                    }
                }
            }
        }
        if (collect != null) {
            collect[0] = Arrays.copyOf(list, listN);
        }
        return total;
    }
}
