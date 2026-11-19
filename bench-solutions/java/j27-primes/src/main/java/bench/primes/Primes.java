package bench.primes;

import java.util.Arrays;

/**
 * Fast prime counting: Lucy_Hedgehog style sieve for pi(n), segmented odd-only
 * sieve for ranges, and nth prime via an analytic estimate, one count and a local sieve.
 */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_RANGE_HI = 100_000_000_000_000L;
    private static final long MAX_SPAN = 100_000_000L;
    private static final int MAX_NTH = 1_000_000_000;
    private static final int SEG_ODDS = 1 << 18;

    private static int[] basePrimes = new int[0];
    private static int baseLimit = 1;

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
        if (lo < 0 || hi < lo || hi > MAX_RANGE_HI || hi - lo > MAX_SPAN) {
            throw new IllegalArgumentException("invalid range: " + lo + ".." + hi);
        }
        long[] total = {0};
        if (lo <= 2 && hi >= 2) {
            total[0] = 1;
        }
        sieveOdd(lo, hi, (first, flags, len) -> {
            int c = 0;
            for (int i = 0; i < len; i++) {
                c += flags[i];
            }
            total[0] += c;
        });
        return total[0];
    }

    /** The n-th prime, nth(1) == 2. */
    public static long nth(int n) {
        if (n < 1 || n > MAX_NTH) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 100_000) {
            return nthSmall(n);
        }
        double ln = Math.log(n);
        double lln = Math.log(ln);
        double est = n * (ln + lln - 1.0 + (lln - 2.0) / ln - (lln * lln - 6.0 * lln + 11.0) / (2.0 * ln * ln));
        long x = Math.max(2L, (long) est);
        long c = lucy(x);
        if (c < n) {
            // the (n - c)-th prime above x
            long[] need = {n - c};
            long[] found = {-1};
            long lo = x + 1;
            final long window = 1L << 22;
            while (found[0] < 0) {
                long hi = lo + window - 1;
                sieveOdd(lo, hi, (first, flags, len) -> {
                    for (int i = 0; i < len && found[0] < 0; i++) {
                        if (flags[i] != 0 && --need[0] == 0) {
                            found[0] = first + 2L * i;
                        }
                    }
                });
                lo = hi + 1;
            }
            return found[0];
        }
        // c >= n: the answer is at or below x; skip the (c - n) largest primes <= x.
        long skip = c - n;
        long hi = x;
        final long window = 1L << 22;
        while (true) {
            long lo = Math.max(2, hi - window + 1);
            // collect primes of [lo, hi] ascending
            long[] buf = new long[(int) Math.min(window, hi - lo + 1) / 2 + 2];
            int[] cnt = {0};
            if (lo <= 2) {
                buf[cnt[0]++] = 2;
            }
            sieveOdd(lo, hi, (first, flags, len) -> {
                for (int i = 0; i < len; i++) {
                    if (flags[i] != 0) {
                        buf[cnt[0]++] = first + 2L * i;
                    }
                }
            });
            if (cnt[0] > skip) {
                return buf[(int) (cnt[0] - 1 - skip)];
            }
            skip -= cnt[0];
            hi = lo - 1;
        }
    }

    private static long nthSmall(int n) {
        if (n < 6) {
            return new long[] {0, 2, 3, 5, 7, 11}[n];
        }
        double ln = Math.log(n);
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
        throw new IllegalStateException("limit too small");
    }

    // ---------------------------------------------------------------- Lucy

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

    private static final int PARALLEL_MIN = 1 << 17;
    private static final int CHUNK = 1 << 15;

    /** large[i] -= small[n / (i p)] - sp for i in [from, to]; reads only small[]. */
    private static void updateLarge(long[] large, long[] small, long n, double nd, int p, long sp, int from, int to) {
        for (int i = from; i <= to; i++) {
            long d = (long) i * p;
            long q = (long) (nd / (double) d);
            if (q * d > n) {
                q--;
            }
            large[i] -= small[(int) q] - sp;
        }
    }

    private static long lucy(long n) {
        if (n < 2) {
            return 0;
        }
        final int r = (int) isqrt(n);
        final long[] small = new long[r + 2];   // small[v] = count of integers in [2, v] not yet sieved
        final long[] large = new long[r + 2];   // large[i] = same for v = n / i
        for (int v = 1; v <= r; v++) {
            small[v] = v - 1;
            large[v] = n / v - 1;
        }
        final double nd = (double) n;
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            final long sp = small[p - 1];
            final long p2 = (long) p * p;
            final int iMax = (int) Math.min(r, n / p2);
            final int split = Math.min(iMax, r / p);
            for (int i = 1; i <= split; i++) {
                large[i] -= large[i * p] - sp;
            }
            if (iMax - split >= PARALLEL_MIN) {
                final int fromI = split + 1;
                final int chunks = (iMax - split + CHUNK - 1) / CHUNK;
                final int pp = p;
                java.util.stream.IntStream.range(0, chunks).parallel().forEach(c -> {
                    int lo = fromI + c * CHUNK;
                    int hi = (int) Math.min((long) iMax, (long) lo + CHUNK - 1);
                    updateLarge(large, small, n, nd, pp, sp, lo, hi);
                });
            } else {
                updateLarge(large, small, n, nd, p, sp, split + 1, iMax);
            }
            long vTop = r;
            for (int q = (int) (vTop / p); q >= p; q--) {
                long delta = small[q] - sp;
                int from = q * p;
                int to = (int) Math.min(vTop, (long) from + p - 1);
                for (int v = to; v >= from; v--) {
                    small[v] -= delta;
                }
            }
        }
        return large[1];
    }

    // ----------------------------------------------------- segmented sieve

    private interface SegmentSink {
        /** flags[i] == 1 iff first + 2 * i is prime, for i in [0, len). */
        void accept(long first, byte[] flags, int len);
    }

    private static synchronized int[] basePrimesUpTo(long limit) {
        if (limit > baseLimit || basePrimes.length == 0) {
            int lim = (int) Math.max(limit, 1000);
            boolean[] comp = new boolean[lim / 2 + 1]; // index i represents 2i + 1
            int[] ps = new int[Math.max(16, (int) (1.3 * lim / Math.log(lim)) + 16)];
            int k = 0;
            for (int i = 1; 2 * i + 1 <= lim; i++) {
                if (!comp[i]) {
                    int pr = 2 * i + 1;
                    ps[k++] = pr;
                    for (long j = (long) pr * pr; j <= lim; j += 2L * pr) {
                        comp[(int) (j / 2)] = true;
                    }
                }
            }
            basePrimes = Arrays.copyOf(ps, k); // odd primes only
            baseLimit = lim;
        }
        return basePrimes;
    }

    /**
     * Streams the primality flags of the odd numbers in [lo, hi] to the sink in ascending
     * consecutive blocks. The prime 2 is not reported; callers handle it.
     */
    private static void sieveOdd(long lo, long hi, SegmentSink sink) {
        long first = Math.max(lo, 3);
        if ((first & 1) == 0) {
            first++;
        }
        long last = (hi & 1) == 0 ? hi - 1 : hi;
        if (first > last) {
            return;
        }
        int[] bp = basePrimesUpTo(isqrt(last) + 1);
        byte[] flags = new byte[SEG_ODDS];
        long segFirst = first;
        while (segFirst <= last) {
            int len = (int) Math.min(SEG_ODDS, (last - segFirst) / 2 + 1);
            long segLast = segFirst + 2L * (len - 1);
            Arrays.fill(flags, 0, len, (byte) 1);
            for (int i = 0; i < bp.length; i++) {
                long p = bp[i];
                long pp = p * p;
                if (pp > segLast) {
                    break;
                }
                long start = Math.max(pp, (segFirst + p - 1) / p * p);
                if ((start & 1) == 0) {
                    start += p;
                }
                int idx = (int) ((start - segFirst) >> 1);
                int step = (int) p;
                for (; idx < len; idx += step) {
                    flags[idx] = 0;
                }
            }
            sink.accept(segFirst, flags, len);
            segFirst = segLast + 2;
        }
    }
}
