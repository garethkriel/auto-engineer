package bench.primes;

import java.util.Arrays;

/** Prime counting (Lucy_Hedgehog), segmented range counting and n-th prime. */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_HI = 100_000_000_000_000L;
    private static final long MAX_SPAN = 100_000_000L;
    private static final int MAX_NTH = 1_000_000_000;
    private static final int SEGMENT = 1 << 21; // odd numbers per segment

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
        if (lo < 0 || hi < lo || hi > MAX_HI || hi - lo > MAX_SPAN) {
            throw new IllegalArgumentException("bad range: " + lo + ".." + hi);
        }
        if (hi < 2) {
            return 0;
        }
        return segmentedCount(Math.max(lo, 2), hi);
    }

    /** The n-th prime, nth(1) = 2. */
    public static long nth(int n) {
        if (n < 1 || n > MAX_NTH) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n <= 10_000) {
            int limit = 105_000; // p_10000 = 104729
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
            throw new IllegalStateException();
        }
        long x = estimate(n);
        long c = lucy(x); // primes <= x
        if (c >= n) {
            long need = c - n + 1; // need-th largest prime <= x
            long hi = x;
            while (true) {
                long lo = Math.max(2, hi - 999_999);
                long[] ps = listPrimes(lo, hi);
                if (ps.length >= need) {
                    return ps[(int) (ps.length - need)];
                }
                need -= ps.length;
                hi = lo - 1;
            }
        }
        long have = c;
        long lo = x + 1;
        while (true) {
            long hi = lo + 999_999;
            long[] ps = listPrimes(lo, hi);
            if (have + ps.length >= n) {
                return ps[(int) (n - have - 1)];
            }
            have += ps.length;
            lo = hi + 1;
        }
    }

    /** Estimate of the n-th prime: inverse of the logarithmic integral. */
    private static long estimate(long n) {
        double lo = 2, hi = 3e10;
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            if (li(mid) < n) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (long) lo;
    }

    /** Logarithmic integral: gamma + ln ln x + sum (ln x)^k / (k * k!). */
    private static double li(double x) {
        double lnx = Math.log(x);
        double term = 1; // lnx^k / k!
        double sum = 0;
        for (int k = 1; k < 150; k++) {
            term *= lnx / k;
            sum += term / k;
        }
        return 0.5772156649015329 + Math.log(lnx) + sum;
    }

    private static long lucy(long n) {
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
        long[] small = new long[r + 2];
        long[] large = new long[r + 2];
        for (int v = 1; v <= r; v++) {
            small[v] = v - 1;
            large[v] = n / v - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            long sp = small[p - 1];
            long p2 = (long) p * p;
            if (p2 > n) {
                break;
            }
            int end = (int) Math.min(r, n / p2);
            int lim = (int) Math.min(end, r / p);
            for (int i = 1; i <= lim; i++) {
                large[i] -= large[i * p] - sp;
            }
            double dn = (double) n;
            for (int i = lim + 1; i <= end; i++) {
                long d = (long) i * p;
                large[i] -= small[(int) (dn / d)] - sp;
            }
            for (int v = r; v >= p2; v--) {
                small[v] -= small[v / p] - sp;
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
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[cnt++] = i;
                for (long j = (long) i * i; j <= lim; j += i) {
                    comp[(int) j] = true;
                }
            }
        }
        return Arrays.copyOf(out, cnt);
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

    private interface Sink {
        void accept(long prime);
    }

    private static long segmentedCount(long lo, long hi) {
        long[] total = {0};
        sieve(lo, hi, p -> total[0]++);
        return total[0];
    }

    private static long[] listPrimes(long lo, long hi) {
        long[] buf = new long[64];
        int[] n = {0};
        long[][] holder = {buf};
        sieve(lo, hi, p -> {
            if (n[0] == holder[0].length) {
                holder[0] = Arrays.copyOf(holder[0], n[0] * 2);
            }
            holder[0][n[0]++] = p;
        });
        return Arrays.copyOf(holder[0], n[0]);
    }

    /** Calls sink for each prime in [lo, hi] in increasing order, lo >= 2. */
    private static void sieve(long lo, long hi, Sink sink) {
        if (lo <= 2 && hi >= 2) {
            sink.accept(2);
        }
        long first = Math.max(lo, 3);
        if ((first & 1) == 0) {
            first++;
        }
        if (first > hi) {
            return;
        }
        int[] base = basePrimes(isqrt(hi));
        boolean[] comp = new boolean[SEGMENT];
        for (long segStart = first; segStart <= hi; segStart += 2L * SEGMENT) {
            long segEnd = Math.min(hi, segStart + 2L * SEGMENT - 2); // inclusive, odd
            int len = (int) ((segEnd - segStart) / 2 + 1);
            Arrays.fill(comp, 0, len, false);
            for (int bi = 1; bi < base.length; bi++) { // skip 2
                long p = base[bi];
                long p2 = p * p;
                if (p2 > segEnd) {
                    break;
                }
                long s;
                if (p2 >= segStart) {
                    s = p2;
                } else {
                    s = (segStart + p - 1) / p * p;
                    if ((s & 1) == 0) {
                        s += p;
                    }
                }
                int step = (int) p; // odd multiples are spaced 2p apart = p slots
                for (long idx = (s - segStart) / 2; idx < len; idx += step) {
                    comp[(int) idx] = true;
                }
            }
            for (int i = 0; i < len; i++) {
                if (!comp[i]) {
                    sink.accept(segStart + 2L * i);
                }
            }
        }
    }
}
