package perf;

/**
 * Immutable sieve of Eratosthenes that stores only odd numbers, one bit each.
 *
 * <p>Bit {@code i} represents the odd number {@code 2i + 1}; a set bit means "composite". 2 is
 * handled separately. Memory is about {@code limit / 16} bytes.
 */
public final class PrimeSieve {
    private final int limit;
    private final long[] composite;
    private final int count;
    /** prefixCount[w] = primes among the odd numbers in words 0..w-1 (excluding 2). */
    private final int[] prefixCount;

    private PrimeSieve(int limit, long[] composite, int[] prefixCount, int count) {
        this.limit = limit;
        this.composite = composite;
        this.prefixCount = prefixCount;
        this.count = count;
    }

    /** Sieves all primes up to and including {@code limit}. */
    public static PrimeSieve of(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
        // Odd numbers 1,3,...,<=limit  -> indexes 0..(limit-1)/2 ; none when limit == 0.
        int bits = limit == 0 ? 0 : (limit - 1) / 2 + 1;
        long[] words = new long[(bits + 63) >>> 6];
        if (bits > 0) {
            words[0] |= 1L; // 1 is not prime
        }
        for (long i = 1; ; i++) {
            long p = 2 * i + 1;
            if (p * p > limit) {
                break;
            }
            if ((words[(int) (i >>> 6)] >>> (i & 63) & 1L) != 0) {
                continue;
            }
            for (long j = (p * p - 1) / 2; j < bits; j += p) {
                words[(int) (j >>> 6)] |= 1L << (j & 63);
            }
        }
        // Mask bits beyond `bits` as composite so they are never counted.
        if ((bits & 63) != 0) {
            words[words.length - 1] |= -1L << (bits & 63);
        }
        int[] prefix = new int[words.length + 1];
        for (int w = 0; w < words.length; w++) {
            prefix[w + 1] = prefix[w] + (64 - Long.bitCount(words[w]));
        }
        int total = prefix[words.length] + (limit >= 2 ? 1 : 0);
        return new PrimeSieve(limit, words, prefix, total);
    }

    /** Returns whether {@code n} is prime; {@code n} must not exceed the sieve limit. */
    public boolean isPrime(int n) {
        if (n > limit) {
            throw new IllegalArgumentException("n " + n + " exceeds limit " + limit);
        }
        if (n < 2) {
            return false;
        }
        if (n == 2) {
            return true;
        }
        if ((n & 1) == 0) {
            return false;
        }
        int i = n >>> 1;
        return (composite[i >>> 6] >>> (i & 63) & 1L) == 0;
    }

    /** Number of primes {@code <= limit}. */
    public int count() {
        return count;
    }

    /** Number of primes {@code <= n}; {@code n} must not exceed the sieve limit. */
    public int countUpTo(int n) {
        if (n > limit) {
            throw new IllegalArgumentException("n " + n + " exceeds limit " + limit);
        }
        if (n < 2) {
            return 0;
        }
        // odd numbers <= n have indexes 0..(n-1)/2
        int last = (n - 1) / 2;
        int w = last >>> 6;
        int bit = last & 63;
        long mask = bit == 63 ? -1L : (1L << (bit + 1)) - 1;
        int odd = prefixCount[w] + Long.bitCount(~composite[w] & mask);
        return odd + 1; // + the prime 2
    }

    /** The k-th prime, 1-based. */
    public int nth(int k) {
        if (k < 1 || k > count) {
            throw new IllegalArgumentException("k out of range 1.." + count + ": " + k);
        }
        if (k == 1) {
            return 2;
        }
        int remaining = k - 1; // among odd primes
        int w = 0;
        while (prefixCount[w + 1] < remaining) {
            w++;
        }
        long free = ~composite[w];
        int need = remaining - prefixCount[w];
        for (int skip = 1; skip < need; skip++) {
            free &= free - 1; // clear lowest set bit
        }
        int index = (w << 6) + Long.numberOfTrailingZeros(free);
        return 2 * index + 1;
    }

    /** All primes in ascending order (a fresh array on every call). */
    public int[] primes() {
        int[] out = new int[count];
        int n = 0;
        if (limit >= 2) {
            out[n++] = 2;
        }
        for (int w = 0; w < composite.length; w++) {
            long free = ~composite[w];
            while (free != 0) {
                int index = (w << 6) + Long.numberOfTrailingZeros(free);
                out[n++] = 2 * index + 1;
                free &= free - 1;
            }
        }
        return out;
    }
}
