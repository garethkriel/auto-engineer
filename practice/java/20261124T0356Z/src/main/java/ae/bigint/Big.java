package ae.bigint;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable arbitrary-precision signed integer.
 *
 * <p>Representation: {@code sign} in {-1, 0, 1} and a little-endian base-2^32 magnitude in
 * {@code int[]} limbs (treated as unsigned). The magnitude is normalised: no high zero limb,
 * and zero is the empty array with sign 0. Nothing from {@code java.math} is used.
 */
public final class Big implements Comparable<Big> {

    private static final long MASK = 0xffffffffL;
    private static final int[] EMPTY = new int[0];
    private static final int KARATSUBA_THRESHOLD = 32;
    private static final AtomicLong ADD_BACKS = new AtomicLong();

    public static final Big ZERO = new Big(0, EMPTY);
    public static final Big ONE = new Big(1, new int[] {1});

    private final int sign;
    private final int[] mag;

    private Big(int sign, int[] mag) {
        this.sign = sign;
        this.mag = mag;
    }

    /** Builds from an unnormalised magnitude and a sign (sign ignored when the magnitude is zero). */
    private static Big of(int sign, int[] magnitude) {
        int[] m = trim(magnitude);
        return m.length == 0 ? ZERO : new Big(sign, m);
    }

    // ------------------------------------------------------------------ construction

    public static Big valueOf(long v) {
        if (v == 0) {
            return ZERO;
        }
        int s = v < 0 ? -1 : 1;
        long a = v < 0 ? -v : v; // Long.MIN_VALUE stays negative: as an unsigned long it is 2^63, which is right
        int lo = (int) a;
        int hi = (int) (a >>> 32);
        return of(s, hi == 0 ? new int[] {lo} : new int[] {lo, hi});
    }

    public static Big parse(String text) {
        return parse(text, 10);
    }

    public static Big parse(String text, int radix) {
        checkRadix(radix);
        if (text == null) {
            throw new NumberFormatException("null");
        }
        int i = 0;
        int s = 1;
        if (!text.isEmpty() && (text.charAt(0) == '-' || text.charAt(0) == '+')) {
            s = text.charAt(0) == '-' ? -1 : 1;
            i = 1;
        }
        if (i >= text.length()) {
            throw new NumberFormatException("no digits: '" + text + "'");
        }
        int chunkDigits = chunkDigits(radix);
        int[] acc = new int[text.length() * 6 / 32 + 2]; // radix <= 36 needs < 6 bits per digit;
        int used = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + chunkDigits);
            long value = 0;
            long scale = 1;
            for (int k = i; k < end; k++) {
                int d = Character.digit(text.charAt(k), radix);
                if (d < 0 || text.charAt(k) > 127) {
                    throw new NumberFormatException("bad digit '" + text.charAt(k) + "' in '" + text + "'");
                }
                value = value * radix + d;
                scale *= radix;
            }
            used = mulAddSmall(acc, used, scale, value);
            i = end;
        }
        return of(s, Arrays.copyOf(acc, used));
    }

    /** acc = acc * factor + addend, in place; returns the new used length. Factor, addend < 2^32. */
    private static int mulAddSmall(int[] acc, int used, long factor, long addend) {
        long carry = addend;
        for (int i = 0; i < used; i++) {
            long p = (acc[i] & MASK) * factor + carry;
            acc[i] = (int) p;
            carry = p >>> 32;
        }
        if (carry != 0) {
            acc[used++] = (int) carry;
        }
        return used;
    }

    private static void checkRadix(int radix) {
        if (radix < 2 || radix > 36) {
            throw new IllegalArgumentException("radix " + radix);
        }
    }

    /** Largest k with radix^k < 2^32 (so a chunk value and its scale fit an unsigned 32-bit limb op). */
    private static int chunkDigits(int radix) {
        int k = 0;
        long p = 1;
        while (p * radix < (1L << 32)) {
            p *= radix;
            k++;
        }
        return k;
    }

    // ------------------------------------------------------------------ simple queries

    public int signum() {
        return sign;
    }

    public int bitLength() {
        return mag.length == 0 ? 0 : mag.length * 32 - Integer.numberOfLeadingZeros(mag[mag.length - 1]);
    }

    public long longValueExact() {
        if (mag.length > 2) {
            throw new ArithmeticException("out of long range");
        }
        long a = 0;
        for (int i = mag.length - 1; i >= 0; i--) {
            a = (a << 32) | (mag[i] & MASK);
        }
        // a is the magnitude as an unsigned 64-bit value
        if (sign >= 0) {
            if (a < 0) {
                throw new ArithmeticException("out of long range");
            }
            return a;
        }
        if (a < 0 && a != Long.MIN_VALUE) { // magnitude above 2^63
            throw new ArithmeticException("out of long range");
        }
        return -a; // for a == Long.MIN_VALUE (2^63) this wraps back to Long.MIN_VALUE, as wanted
    }

    // ------------------------------------------------------------------ comparison

    @Override
    public int compareTo(Big o) {
        if (sign != o.sign) {
            return Integer.compare(sign, o.sign);
        }
        int c = cmpMag(mag, o.mag);
        return sign >= 0 ? c : -c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Big b && b.sign == sign && Arrays.equals(b.mag, mag);
    }

    @Override
    public int hashCode() {
        return 31 * sign + Arrays.hashCode(mag);
    }

    // ------------------------------------------------------------------ add / subtract

    public Big negate() {
        return sign == 0 ? this : new Big(-sign, mag);
    }

    public Big abs() {
        return sign < 0 ? new Big(1, mag) : this;
    }

    public Big add(Big o) {
        if (o.sign == 0) {
            return this;
        }
        if (sign == 0) {
            return o;
        }
        if (sign == o.sign) {
            return new Big(sign, addMag(mag, o.mag));
        }
        int c = cmpMag(mag, o.mag);
        if (c == 0) {
            return ZERO;
        }
        return c > 0 ? new Big(sign, subMag(mag, o.mag)) : new Big(o.sign, subMag(o.mag, mag));
    }

    public Big subtract(Big o) {
        return add(o.negate());
    }

    // ------------------------------------------------------------------ multiply

    public Big multiply(Big o) {
        if (sign == 0 || o.sign == 0) {
            return ZERO;
        }
        return of(sign * o.sign, mulKaratsuba(mag, o.mag, KARATSUBA_THRESHOLD));
    }

    /** Schoolbook product of two normalised magnitudes. */
    static int[] mulSchool(int[] a, int[] b) {
        if (a.length == 0 || b.length == 0) {
            return EMPTY;
        }
        int[] r = new int[a.length + b.length];
        for (int i = 0; i < a.length; i++) {
            long carry = 0;
            long ai = a[i] & MASK;
            for (int j = 0; j < b.length; j++) {
                long t = ai * (b[j] & MASK) + (r[i + j] & MASK) + carry;
                r[i + j] = (int) t;
                carry = t >>> 32;
            }
            r[i + b.length] = (int) carry;
        }
        return trim(r);
    }

    /** Karatsuba product of two normalised magnitudes; falls back to schoolbook at or below {@code threshold} limbs. */
    static int[] mulKaratsuba(int[] a, int[] b, int threshold) {
        if (a.length == 0 || b.length == 0) {
            return EMPTY;
        }
        int n = Math.max(a.length, b.length);
        if (Math.min(a.length, b.length) <= threshold || n <= threshold) {
            return mulSchool(a, b);
        }
        int k = (n + 1) / 2;
        int[] a0 = low(a, k);
        int[] a1 = high(a, k);
        int[] b0 = low(b, k);
        int[] b1 = high(b, k);
        int[] z0 = mulKaratsuba(a0, b0, threshold);
        int[] z2 = mulKaratsuba(a1, b1, threshold);
        int[] z1 = subMag(subMag(mulKaratsuba(addMag(a0, a1), addMag(b0, b1), threshold), z0), z2);
        int[] r = new int[a.length + b.length + 1];
        addInto(r, z0, 0);
        addInto(r, z1, k);
        addInto(r, z2, 2 * k);
        return trim(r);
    }

    private static int[] low(int[] a, int k) {
        return trim(Arrays.copyOf(a, Math.min(k, a.length)));
    }

    private static int[] high(int[] a, int k) {
        return a.length <= k ? EMPTY : Arrays.copyOfRange(a, k, a.length);
    }

    /** r += x << (32 * offset); r must be long enough that no carry leaves it. */
    private static void addInto(int[] r, int[] x, int offset) {
        long carry = 0;
        int i = 0;
        for (; i < x.length; i++) {
            long t = (r[offset + i] & MASK) + (x[i] & MASK) + carry;
            r[offset + i] = (int) t;
            carry = t >>> 32;
        }
        for (int j = offset + i; carry != 0; j++) {
            long t = (r[j] & MASK) + carry;
            r[j] = (int) t;
            carry = t >>> 32;
        }
    }

    // ------------------------------------------------------------------ division

    public Big[] divideAndRemainder(Big d) {
        if (d.sign == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (sign == 0) {
            return new Big[] {ZERO, ZERO};
        }
        int[][] qr = divMag(mag, d.mag);
        return new Big[] {of(sign * d.sign, qr[0]), of(sign, qr[1])};
    }

    public Big divide(Big d) {
        return divideAndRemainder(d)[0];
    }

    public Big remainder(Big d) {
        return divideAndRemainder(d)[1];
    }

    /** Non-negative residue; the modulus must be positive. */
    public Big mod(Big m) {
        if (m.sign <= 0) {
            throw new ArithmeticException("modulus must be positive");
        }
        Big r = remainder(m);
        return r.sign < 0 ? r.add(m) : r;
    }

    /** Number of times Knuth's step D6 (add back) ran, for tests. */
    static long addBacks() {
        return ADD_BACKS.get();
    }

    /** Knuth algorithm D on normalised magnitudes; returns {quotient, remainder}. */
    private static int[][] divMag(int[] u, int[] v) {
        if (cmpMag(u, v) < 0) {
            return new int[][] {EMPTY, u};
        }
        int n = v.length;
        int m = u.length;
        if (n == 1) {
            long d = v[0] & MASK;
            int[] q = new int[m];
            long r = 0;
            for (int i = m - 1; i >= 0; i--) {
                long cur = (r << 32) | (u[i] & MASK);
                q[i] = (int) Long.divideUnsigned(cur, d);
                r = Long.remainderUnsigned(cur, d);
            }
            return new int[][] {trim(q), r == 0 ? EMPTY : new int[] {(int) r}};
        }
        int s = Integer.numberOfLeadingZeros(v[n - 1]);
        int[] vn = shiftLeftBits(v, s, n);
        int[] un = shiftLeftBits(u, s, m + 1);
        int[] q = new int[m - n + 1];
        long vTop = vn[n - 1] & MASK;
        long vNext = vn[n - 2] & MASK;
        for (int j = m - n; j >= 0; j--) {
            long num = ((un[j + n] & MASK) << 32) | (un[j + n - 1] & MASK);
            long qhat = Long.divideUnsigned(num, vTop);
            long rhat = Long.remainderUnsigned(num, vTop);
            while (qhat > MASK
                    || Long.compareUnsigned(qhat * vNext, (rhat << 32) | (un[j + n - 2] & MASK)) > 0) {
                qhat--;
                rhat += vTop;
                if (rhat > MASK) {
                    break;
                }
            }
            long borrow = 0;
            long carry = 0;
            for (int i = 0; i < n; i++) {
                long p = qhat * (vn[i] & MASK) + carry;
                carry = p >>> 32;
                long t = (un[i + j] & MASK) - (p & MASK) - borrow;
                un[i + j] = (int) t;
                borrow = t >>> 63;
            }
            long t = (un[j + n] & MASK) - carry - borrow;
            un[j + n] = (int) t;
            if (t < 0) {
                ADD_BACKS.incrementAndGet();
                qhat--;
                long c = 0;
                for (int i = 0; i < n; i++) {
                    long sum = (un[i + j] & MASK) + (vn[i] & MASK) + c;
                    un[i + j] = (int) sum;
                    c = sum >>> 32;
                }
                un[j + n] += (int) c;
            }
            q[j] = (int) qhat;
        }
        int[] rem = shiftRightBits(Arrays.copyOf(un, n), s);
        return new int[][] {trim(q), trim(rem)};
    }

    /** Copy of {@code a} shifted left by 0..31 bits into an array of {@code length} limbs (length >= a.length). */
    private static int[] shiftLeftBits(int[] a, int s, int length) {
        int[] r = new int[length];
        if (s == 0) {
            System.arraycopy(a, 0, r, 0, a.length);
            return r;
        }
        for (int i = 0; i < a.length; i++) {
            r[i] |= a[i] << s;
            if (i + 1 < length) {
                r[i + 1] |= a[i] >>> (32 - s);
            }
        }
        return r;
    }

    private static int[] shiftRightBits(int[] a, int s) {
        if (s == 0) {
            return a;
        }
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (a[i] >>> s) | (i + 1 < a.length ? a[i + 1] << (32 - s) : 0);
        }
        return r;
    }

    // ------------------------------------------------------------------ number theory

    public Big pow(int e) {
        if (e < 0) {
            throw new ArithmeticException("negative exponent");
        }
        Big result = ONE;
        Big base = this;
        int k = e;
        while (k > 0) {
            if ((k & 1) == 1) {
                result = result.multiply(base);
            }
            k >>= 1;
            if (k > 0) {
                base = base.multiply(base);
            }
        }
        return result;
    }

    public Big modPow(Big e, Big m) {
        if (m.sign <= 0) {
            throw new ArithmeticException("modulus must be positive");
        }
        if (e.sign < 0) {
            throw new ArithmeticException("negative exponent");
        }
        Big result = ONE.mod(m);
        Big base = mod(m);
        for (int i = 0; i < e.bitLength(); i++) {
            if (e.testBit(i)) {
                result = result.multiply(base).mod(m);
            }
            base = base.multiply(base).mod(m);
        }
        return result;
    }

    private boolean testBit(int i) {
        int limb = i / 32;
        return limb < mag.length && ((mag[limb] >>> (i % 32)) & 1) != 0;
    }

    public Big gcd(Big o) {
        Big a = abs();
        Big b = o.abs();
        while (b.sign != 0) {
            Big t = a.remainder(b);
            a = b;
            b = t;
        }
        return a;
    }

    public Big modInverse(Big m) {
        if (m.sign <= 0) {
            throw new ArithmeticException("modulus must be positive");
        }
        if (m.equals(ONE)) {
            return ZERO;
        }
        // Extended Euclid on (m, a mod m), tracking only the coefficient of a.
        Big r0 = m;
        Big r1 = mod(m);
        Big t0 = ZERO;
        Big t1 = ONE;
        while (r1.sign != 0) {
            Big[] qr = r0.divideAndRemainder(r1);
            Big t2 = t0.subtract(qr[0].multiply(t1));
            r0 = r1;
            r1 = qr[1];
            t0 = t1;
            t1 = t2;
        }
        if (!r0.equals(ONE)) {
            throw new ArithmeticException("not invertible");
        }
        return t0.mod(m);
    }

    /** Floor of the square root (Newton iteration from an over-estimate). */
    public Big isqrt() {
        if (sign < 0) {
            throw new ArithmeticException("negative");
        }
        if (sign == 0) {
            return ZERO;
        }
        Big x = ONE.shiftLeft((bitLength() + 1) / 2); // >= sqrt(this)
        while (true) {
            Big y = x.add(divide(x)).shiftRight(1);
            if (y.compareTo(x) >= 0) {
                return x;
            }
            x = y;
        }
    }

    // ------------------------------------------------------------------ shifts

    public Big shiftLeft(int n) {
        if (n < 0) {
            return shiftRight(-n);
        }
        if (sign == 0 || n == 0) {
            return this;
        }
        int limbs = n / 32;
        int bits = n % 32;
        int[] r = new int[mag.length + limbs + 1];
        for (int i = 0; i < mag.length; i++) {
            r[i + limbs] |= mag[i] << bits;
            if (bits != 0) {
                r[i + limbs + 1] |= mag[i] >>> (32 - bits);
            }
        }
        return of(sign, r);
    }

    /** Arithmetic shift: rounds toward negative infinity. */
    public Big shiftRight(int n) {
        if (n < 0) {
            return shiftLeft(-n);
        }
        if (sign == 0 || n == 0) {
            return this;
        }
        if (sign > 0) {
            return of(1, shiftMagRight(mag, n));
        }
        // floor(-m / 2^n) = -(((m - 1) >> n) + 1)
        int[] mMinusOne = subMag(mag, new int[] {1});
        return of(-1, addMag(shiftMagRight(mMinusOne, n), new int[] {1}));
    }

    private static int[] shiftMagRight(int[] a, int n) {
        int limbs = n / 32;
        int bits = n % 32;
        if (limbs >= a.length) {
            return EMPTY;
        }
        int[] r = new int[a.length - limbs];
        for (int i = 0; i < r.length; i++) {
            r[i] = a[i + limbs] >>> bits;
            if (bits != 0 && i + limbs + 1 < a.length) {
                r[i] |= a[i + limbs + 1] << (32 - bits);
            }
        }
        return trim(r);
    }

    // ------------------------------------------------------------------ text

    @Override
    public String toString() {
        return toString(10);
    }

    public String toString(int radix) {
        checkRadix(radix);
        if (sign == 0) {
            return "0";
        }
        int chunkDigits = chunkDigits(radix);
        long chunk = 1;
        for (int i = 0; i < chunkDigits; i++) {
            chunk *= radix;
        }
        int[] work = mag.clone();
        int len = work.length;
        StringBuilder sb = new StringBuilder();
        while (len > 0) {
            long rem = 0;
            for (int i = len - 1; i >= 0; i--) {
                long cur = (rem << 32) | (work[i] & MASK);
                work[i] = (int) Long.divideUnsigned(cur, chunk);
                rem = Long.remainderUnsigned(cur, chunk);
            }
            while (len > 0 && work[len - 1] == 0) {
                len--;
            }
            String digits = Long.toString(rem, radix);
            if (len > 0) { // not the most significant chunk: pad to full width
                sb.append(new StringBuilder(digits).reverse());
                for (int pad = digits.length(); pad < chunkDigits; pad++) {
                    sb.append('0');
                }
            } else {
                sb.append(new StringBuilder(digits).reverse());
            }
        }
        if (sign < 0) {
            sb.append('-');
        }
        return sb.reverse().toString();
    }

    // ------------------------------------------------------------------ magnitude helpers

    private static int[] trim(int[] a) {
        int n = a.length;
        while (n > 0 && a[n - 1] == 0) {
            n--;
        }
        return n == a.length ? a : Arrays.copyOf(a, n);
    }

    private static int cmpMag(int[] a, int[] b) {
        if (a.length != b.length) {
            return a.length < b.length ? -1 : 1;
        }
        for (int i = a.length - 1; i >= 0; i--) {
            if (a[i] != b[i]) {
                return Integer.compareUnsigned(a[i], b[i]) < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] addMag(int[] a, int[] b) {
        int[] big = a.length >= b.length ? a : b;
        int[] small = big == a ? b : a;
        int[] r = new int[big.length + 1];
        long carry = 0;
        for (int i = 0; i < big.length; i++) {
            long t = (big[i] & MASK) + (i < small.length ? small[i] & MASK : 0) + carry;
            r[i] = (int) t;
            carry = t >>> 32;
        }
        r[big.length] = (int) carry;
        return trim(r);
    }

    /** a - b for a >= b. */
    private static int[] subMag(int[] a, int[] b) {
        int[] r = new int[a.length];
        long borrow = 0;
        for (int i = 0; i < a.length; i++) {
            long t = (a[i] & MASK) - (i < b.length ? b[i] & MASK : 0) - borrow;
            r[i] = (int) t;
            borrow = t < 0 ? 1 : 0;
        }
        return trim(r);
    }
}
