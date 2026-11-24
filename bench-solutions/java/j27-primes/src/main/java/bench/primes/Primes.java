package bench.primes;

import java.util.Arrays;

/** Prime counting (Lucy_Hedgehog), segmented sieve ranges and the n-th prime. */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_HI = 100_000_000_000_000L;
    private static final long MAX_SPAN = 100_000_000L;
    private static final int SEG_ODDS = 1 << 19;

    private Primes() { }

    public static long count(long n) {
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        return pi(n);
    }

    public static long countInRange(long lo, long hi) {
        if (lo < 0 || hi < lo || hi > MAX_HI || hi - lo > MAX_SPAN) {
            throw new IllegalArgumentException("bad range");
        }
        return rangeCount(lo, hi);
    }

    public static long nth(int n) {
        if (n < 1 || n > 1_000_000_000) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n <= 200_000) {
            // p(200000) = 2750159
            long[] ps = rangeList(2, 2_750_200);
            return ps[n - 1];
        }
        long x = inverseLi(n);
        long c = pi(x); // primes <= x
        final long w = 1L << 21;
        if (c < n) {
            long lo = x + 1;
            while (true) {
                long[] ps = rangeList(lo, lo + w - 1);
                if (c + ps.length >= n) {
                    return ps[(int) (n - c - 1)];
                }
                c += ps.length;
                lo += w;
            }
        } else {
            long hi = x;
            while (true) {
                long lo = Math.max(2, hi - w + 1);
                long[] ps = rangeList(lo, hi);
                long below = c - ps.length;
                if (below < n) {
                    return ps[(int) (n - below - 1)];
                }
                c = below;
                hi = lo - 1;
            }
        }
    }

    // ---- li inverse estimate ----
    private static double li(double x) {
        double lnx = Math.log(x);
        double gamma = 0.5772156649015329;
        double sum = 0, term = 1;
        for (int k = 1; k < 200; k++) {
            term *= lnx / k;
            double add = term / k;
            sum += add;
            if (add < 1e-17 * sum) {
                break;
            }
        }
        return gamma + Math.log(lnx) + sum;
    }

    private static long inverseLi(long n) {
        double x = n * (Math.log(n) + Math.log(Math.log(n)));
        for (int i = 0; i < 60; i++) {
            double f = li(x) - n;
            double nx = x - f * Math.log(x);
            if (Math.abs(nx - x) < 0.5) {
                x = nx;
                break;
            }
            x = nx;
        }
        return (long) x;
    }

    // ---- Lucy_Hedgehog prime counting ----
    private static long pi(long n) {
        if (n < 2) {
            return 0;
        }
        int r = (int) Math.sqrt((double) n);
        while ((long) r * r > n) {
            r--;
        }
        while ((long) (r + 1) * (r + 1) <= n) {
            r++;
        }
        long[] small = new long[r + 2];   // small[i] = count of survivors <= i
        long[] large = new long[r + 2];   // large[i] = count of survivors <= n/i
        long[] nOver = new long[r + 2];
        for (int i = 1; i <= r; i++) {
            small[i] = i - 1;
            nOver[i] = n / i;
            large[i] = nOver[i] - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            long pc = small[p - 1];
            long p2 = (long) p * p;
            int end = (int) Math.min(r, n / p2);
            int lim = Math.min(end, r / p);
            for (int i = 1; i <= lim; i++) {
                large[i] -= large[i * p] - pc;
            }
            for (int i = lim + 1; i <= end; i++) {
                long v = nOver[i] / p;
                large[i] -= small[(int) v] - pc;
            }
            for (int i = r; i >= p2; i--) {
                small[i] -= small[i / p] - pc;
            }
        }
        return large[1];
    }

    // ---- segmented sieve ----
    private static int[] basePrimes(long limit) {
        int m = (int) Math.min(limit, Integer.MAX_VALUE - 8);
        boolean[] comp = new boolean[m + 1];
        int cnt = 0;
        for (int i = 2; i <= m; i++) {
            if (!comp[i]) {
                cnt++;
                for (long j = (long) i * i; j <= m; j += i) {
                    comp[(int) j] = true;
                }
            }
        }
        int[] res = new int[cnt];
        int k = 0;
        for (int i = 2; i <= m; i++) {
            if (!comp[i]) {
                res[k++] = i;
            }
        }
        return res;
    }

    private static long isqrt(long v) {
        long r = (long) Math.sqrt((double) v);
        while (r * r > v) {
            r--;
        }
        while ((r + 1) * (r + 1) <= v) {
            r++;
        }
        return r;
    }

    /** Sieves the odd numbers first, first+2, ..., first+2(k-1) into comp (true = composite). */
    private static void sieveOdds(long first, int k, int[] bp, int bpCount, boolean[] comp) {
        Arrays.fill(comp, 0, k, false);
        long last = first + 2L * (k - 1);
        for (int j = 0; j < bpCount; j++) {
            long p = bp[j];
            if (p == 2) {
                continue;
            }
            long p2 = p * p;
            if (p2 > last) {
                break;
            }
            long m;
            if (p2 >= first) {
                m = p2;
            } else {
                m = (first + p - 1) / p * p;
                if ((m & 1) == 0) {
                    m += p;
                }
            }
            for (long idx = (m - first) >> 1; idx < k; idx += p) {
                comp[(int) idx] = true;
            }
        }
    }

    private static long rangeCount(long lo, long hi) {
        long total = 0;
        if (lo <= 2 && hi >= 2) {
            total++;
        }
        long start = Math.max(lo, 3);
        if ((start & 1) == 0) {
            start++;
        }
        if (start > hi) {
            return total;
        }
        int[] bp = basePrimes(isqrt(hi) + 1);
        boolean[] comp = new boolean[SEG_ODDS];
        long first = start;
        while (first <= hi) {
            long remaining = (hi - first) / 2 + 1;
            int k = (int) Math.min(SEG_ODDS, remaining);
            sieveOdds(first, k, bp, bp.length, comp);
            int c = 0;
            for (int i = 0; i < k; i++) {
                if (!comp[i]) {
                    c++;
                }
            }
            total += c;
            first += 2L * k;
        }
        return total;
    }

    private static long[] rangeList(long lo, long hi) {
        long[] out = new long[1024];
        int n = 0;
        if (lo <= 2 && hi >= 2) {
            out[n++] = 2;
        }
        long start = Math.max(lo, 3);
        if ((start & 1) == 0) {
            start++;
        }
        if (start <= hi) {
            int[] bp = basePrimes(isqrt(hi) + 1);
            boolean[] comp = new boolean[SEG_ODDS];
            long first = start;
            while (first <= hi) {
                long remaining = (hi - first) / 2 + 1;
                int k = (int) Math.min(SEG_ODDS, remaining);
                sieveOdds(first, k, bp, bp.length, comp);
                for (int i = 0; i < k; i++) {
                    if (!comp[i]) {
                        if (n == out.length) {
                            out = Arrays.copyOf(out, n * 2);
                        }
                        out[n++] = first + 2L * i;
                    }
                }
                first += 2L * k;
            }
        }
        return Arrays.copyOf(out, n);
    }
}
