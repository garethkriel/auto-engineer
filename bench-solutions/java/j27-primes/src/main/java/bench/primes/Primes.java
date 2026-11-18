package bench.primes;

/** Fast prime counting (Lucy_Hedgehog), segmented range counting and n-th prime. */
public final class Primes {
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_RANGE_HI = 100_000_000_000_000L;
    private static final long MAX_RANGE_SPAN = 100_000_000L;
    private static final int MAX_NTH = 1_000_000_000;

    private Primes() {
    }

    /** Number of primes p with p &lt;= n, for 0 &lt;= n &lt;= 10^12. */
    public static long count(long n) {
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        return pi(n);
    }

    /** Number of primes p with lo &lt;= p &lt;= hi. */
    public static long countInRange(long lo, long hi) {
        if (lo < 0 || lo > hi || hi > MAX_RANGE_HI || hi - lo > MAX_RANGE_SPAN) {
            throw new IllegalArgumentException("range out of bounds: " + lo + ".." + hi);
        }
        return segmentedCount(lo, hi);
    }

    /** The n-th prime, nth(1) = 2. */
    public static long nth(int n) {
        if (n < 1 || n > MAX_NTH) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 100) {
            return smallNth(n);
        }
        double ln = Math.log(n);
        double lln = Math.log(ln);
        // Dusart: p_n >= n (ln n + ln ln n - 1) for n >= 2; stay safely below it.
        long x = (long) Math.floor(n * (ln + lln - 1.0)) - 1000;
        while (true) {
            long c = pi(x);
            if (c < n) {
                return scanForward(x + 1, n - c);
            }
            x = x - Math.max(1000L, x / 100); // defensive; should not happen
        }
    }

    private static long smallNth(int n) {
        int found = 0;
        for (long k = 2; ; k++) {
            boolean prime = true;
            for (long d = 2; d * d <= k; d++) {
                if (k % d == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime && ++found == n) {
                return k;
            }
        }
    }

    /** Returns the need-th prime that is &gt;= from. */
    private static long scanForward(long from, long need) {
        final long seg = 1L << 22;
        long lo = from;
        long[] result = {-1};
        while (true) {
            long cnt = sieve(lo, lo + seg - 1, need, result);
            if (result[0] >= 0) {
                return result[0];
            }
            need -= cnt;
            lo += seg;
        }
    }

    // ---------------------------------------------------------------- Lucy_Hedgehog

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
        // small[i] = pi-candidates count for value i; large[i] for value n / i
        int[] small = new int[r + 2];
        long[] large = new long[r + 2];
        for (int i = 1; i <= r; i++) {
            small[i] = i - 1;
            large[i] = n / i - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            int pc = small[p - 1];
            long p2 = (long) p * p;
            if (p2 > n) {
                break;
            }
            int end = (int) Math.min(r, n / p2);
            int lim = Math.min(end, r / p);
            for (int i = 1; i <= lim; i++) {
                large[i] -= large[i * p] - pc;
            }
            for (int i = lim + 1; i <= end; i++) {
                long d = (long) i * p;
                large[i] -= small[(int) (n / d)] - pc;
            }
            for (int i = r; i >= p2; i--) {
                small[i] -= small[i / p] - pc;
            }
        }
        return large[1];
    }

    // ---------------------------------------------------------------- segmented sieve

    private static volatile int[] basePrimesCache = new int[0];
    private static volatile int basePrimesLimit = 1;

    private static int[] basePrimes(long limit) {
        int[] cached = basePrimesCache;
        if (basePrimesLimit >= limit) {
            return cached;
        }
        synchronized (Primes.class) {
            if (basePrimesLimit >= limit) {
                return basePrimesCache;
            }
            int lim = (int) Math.max(limit, 1000);
            boolean[] comp = new boolean[lim + 1];
            int cnt = 0;
            for (int i = 2; i <= lim; i++) {
                if (!comp[i]) {
                    cnt++;
                    for (long j = (long) i * i; j <= lim; j += i) {
                        comp[(int) j] = true;
                    }
                }
            }
            int[] ps = new int[cnt];
            int k = 0;
            for (int i = 2; i <= lim; i++) {
                if (!comp[i]) {
                    ps[k++] = i;
                }
            }
            basePrimesCache = ps;
            basePrimesLimit = lim;
            return ps;
        }
    }

    private static long segmentedCount(long lo, long hi) {
        return sieve(lo, hi, Long.MAX_VALUE, null);
    }

    /**
     * Counts primes in [lo, hi]. If stopAt is reached and result != null, stores the stopAt-th prime of the
     * range in result[0] and stops.
     */
    private static long sieve(long lo, long hi, long stopAt, long[] result) {
        if (hi < 2) {
            return 0;
        }
        if (lo < 2) {
            lo = 2;
        }
        long count = 0;
        if (lo <= 2) {
            count++; // the prime 2
            if (result != null && count == stopAt) {
                result[0] = 2;
                return count;
            }
            lo = 3;
        }
        if (lo > hi) {
            return count;
        }
        // odd numbers only: index i <-> value base + 2 i, where base is the first odd >= lo
        long base = lo | 1L;
        if (base > hi) {
            return count;
        }
        long numOdd = (hi - base) / 2 + 1;
        long sq = (long) Math.sqrt((double) hi);
        while (sq * sq > hi) {
            sq--;
        }
        while ((sq + 1) * (sq + 1) <= hi) {
            sq++;
        }
        int[] ps = basePrimes(sq);
        int np = 0;
        while (np < ps.length && ps[np] <= sq) {
            np++;
        }
        final int segLen = 1 << 17; // odd numbers per segment
        boolean[] comp = new boolean[segLen];
        // next[j] = index (relative to base, as long) of next odd multiple for prime ps[j+1]
        long[] next = new long[np];
        for (int j = 1; j < np; j++) {
            long p = ps[j];
            long start = Math.max(p * p, ((base + p - 1) / p) * p);
            if ((start & 1L) == 0) {
                start += p;
            }
            next[j] = (start - base) / 2;
        }
        for (long segStart = 0; segStart < numOdd; segStart += segLen) {
            int len = (int) Math.min(segLen, numOdd - segStart);
            java.util.Arrays.fill(comp, 0, len, false);
            long segEnd = segStart + len; // exclusive
            for (int j = 1; j < np; j++) {
                long idx = next[j];
                if (idx >= segEnd) {
                    continue;
                }
                int p = ps[j];
                int i = (int) (idx - segStart);
                for (; i < len; i += p) {
                    comp[i] = true;
                }
                next[j] = segStart + i;
            }
            if (result == null) {
                for (int i = 0; i < len; i++) {
                    if (!comp[i]) {
                        count++;
                    }
                }
            } else {
                for (int i = 0; i < len; i++) {
                    if (!comp[i] && ++count == stopAt) {
                        result[0] = base + 2 * (segStart + i);
                        return count;
                    }
                }
            }
        }
        return count;
    }
}
