package bench.primes;

import java.util.Arrays;

/** Prime counting, range counting and nth prime. */
public final class Primes {
    private Primes() { }

    private static final long MAX_COUNT = 1_000_000_000_000L;
    private static final long MAX_RANGE = 100_000_000_000_000L;
    private static final long MAX_WINDOW = 100_000_000L;

    /** Number of primes p with p <= n (Lucy_Hedgehog). */
    public static long count(long n) {
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 2) {
            return 0;
        }
        int r = (int) Math.sqrt((double) n);
        while ((long) r * r > n) r--;
        while ((long) (r + 1) * (r + 1) <= n) r++;
        long[] small = new long[r + 2];
        long[] large = new long[r + 2];
        for (int i = 1; i <= r; i++) {
            small[i] = i - 1;
            large[i] = n / i - 1;
        }
        for (int p = 2; p <= r; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            long pc = small[p - 1];
            long p2 = (long) p * p;
            if (p2 > n) {
                break;
            }
            int end = (int) Math.min(r, n / p2);
            int lim = (int) Math.min(end, r / p);
            for (int i = 1; i <= lim; i++) {
                large[i] -= large[i * p] - pc;
            }
            for (int i = lim + 1; i <= end; i++) {
                large[i] -= small[(int) (n / ((long) i * p))] - pc;
            }
            for (int i = r; i >= p2 && i >= 1; i--) {
                small[i] -= small[i / p] - pc;
            }
        }
        return large[1];
    }

    /** Number of primes p with lo <= p <= hi (segmented sieve). */
    public static long countInRange(long lo, long hi) {
        if (lo < 0 || hi < lo || hi > MAX_RANGE || hi - lo > MAX_WINDOW) {
            throw new IllegalArgumentException("bad range");
        }
        long total = 0;
        if (hi < 2) {
            return 0;
        }
        if (lo <= 2) {
            total++;
        }
        long first = Math.max(lo, 3);
        if ((first & 1) == 0) first++;
        if (first > hi) {
            return total;
        }
        int[] base = basePrimes(isqrt(hi));
        final int seg = 1 << 20; // odd numbers per segment
        boolean[] comp = new boolean[seg];
        long numOdd = (hi - first) / 2 + 1;
        for (long done = 0; done < numOdd; done += seg) {
            int len = (int) Math.min(seg, numOdd - done);
            long start = first + 2 * done;
            long stop = start + 2L * (len - 1);
            Arrays.fill(comp, 0, len, false);
            for (int bi = 1; bi < base.length; bi++) { // skip 2
                long p = base[bi];
                long sq = p * p;
                if (sq > stop) break;
                long m = sq >= start ? sq : ((start + p - 1) / p) * p;
                if ((m & 1) == 0) m += p;
                for (long k = (m - start) >> 1; k < len; k += p) {
                    comp[(int) k] = true;
                }
            }
            for (int i = 0; i < len; i++) {
                if (!comp[i]) total++;
            }
        }
        return total;
    }

    /** The n-th prime, nth(1) = 2. */
    public static long nth(int n) {
        if (n < 1 || n > 1_000_000_000) {
            throw new IllegalArgumentException("n out of range: " + n);
        }
        if (n < 6) {
            return new long[] {0, 2, 3, 5, 7, 11}[n];
        }
        double ln = Math.log(n);
        double lln = Math.log(ln);
        if (n <= 200_000) {
            int limit = (int) (n * (ln + lln)) + 10;
            int[] ps = basePrimes(limit);
            return ps[n - 1];
        }
        double est = n * (ln + lln - 1 + (lln - 2) / ln - (lln * lln - 6 * lln + 11) / (2 * ln * ln));
        long x = (long) est;
        long c = count(x);
        final long chunk = 1L << 24;
        if (c < n) {
            long lo = x + 1;
            while (true) {
                long hi = lo + chunk - 1;
                long[] ps = primesIn(lo, hi);
                if (c + ps.length >= n) {
                    return ps[(int) (n - c - 1)];
                }
                c += ps.length;
                lo = hi + 1;
            }
        } else {
            // c primes <= x; the n-th is the (c-n+1)-th prime counting down from x
            long need = c - n + 1;
            long hi = x;
            while (true) {
                long lo = Math.max(2, hi - chunk + 1);
                long[] ps = primesIn(lo, hi);
                if (ps.length >= need) {
                    return ps[(int) (ps.length - need)];
                }
                need -= ps.length;
                hi = lo - 1;
            }
        }
    }

    private static long[] primesIn(long lo, long hi) {
        int cnt = (int) (hi - lo + 1);
        boolean[] comp = new boolean[cnt];
        int[] base = basePrimes(isqrt(hi));
        for (int p : base) {
            long sq = (long) p * p;
            if (sq > hi) break;
            long m = Math.max(sq, ((lo + p - 1) / p) * p);
            for (long k = m - lo; k < cnt; k += p) {
                comp[(int) k] = true;
            }
        }
        long[] out = new long[cnt];
        int c = 0;
        for (int i = 0; i < cnt; i++) {
            long v = lo + i;
            if (v >= 2 && !comp[i]) out[c++] = v;
        }
        return Arrays.copyOf(out, c);
    }

    private static long isqrt(long v) {
        long r = (long) Math.sqrt((double) v);
        while (r * r > v) r--;
        while ((r + 1) * (r + 1) <= v) r++;
        return r;
    }

    /** All primes <= limit. */
    private static int[] basePrimes(long limitL) {
        int limit = (int) limitL;
        if (limit < 2) return new int[0];
        boolean[] comp = new boolean[limit + 1];
        int c = 0;
        for (int i = 2; i <= limit; i++) {
            if (!comp[i]) {
                c++;
                for (long j = (long) i * i; j <= limit; j += i) comp[(int) j] = true;
            }
        }
        int[] out = new int[c];
        int k = 0;
        for (int i = 2; i <= limit; i++) if (!comp[i]) out[k++] = i;
        return out;
    }
}
