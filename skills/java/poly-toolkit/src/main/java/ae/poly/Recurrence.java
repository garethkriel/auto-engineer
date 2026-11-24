package ae.poly;


/** Linear recurrences over {@link Mod#P}: Berlekamp-Massey and fast n-th term. */
public final class Recurrence {
    private Recurrence() {}

    /**
     * Shortest linear recurrence c (length L) with {@code s[i] = sum_{j=1..L} c[j-1] * s[i-j]} for all i >= L.
     * Returns an empty array for the all-zero (or empty) sequence.
     */
    public static long[] berlekampMassey(long[] seq) {
        int n = seq.length;
        long[] s = new long[n];
        for (int i = 0; i < n; i++) {
            s[i] = Mod.norm(seq[i]);
        }
        long[] cur = new long[n + 1]; // connection polynomial C, C[0] = 1
        long[] prev = new long[n + 1]; // B
        cur[0] = 1;
        prev[0] = 1;
        int len = 0;
        int shift = 1;
        long prevDisc = 1;
        for (int i = 0; i < n; i++) {
            long d = s[i];
            for (int j = 1; j <= len; j++) {
                d = Mod.add(d, Mod.mul(cur[j], s[i - j]));
            }
            if (d == 0) {
                shift++;
                continue;
            }
            long coef = Mod.mul(d, Mod.inv(prevDisc));
            long[] saved = len * 2 <= i ? cur.clone() : null;
            for (int j = 0; j + shift <= n; j++) {
                cur[j + shift] = Mod.sub(cur[j + shift], Mod.mul(coef, prev[j]));
            }
            if (saved != null) {
                len = i + 1 - len;
                prev = saved;
                prevDisc = d;
                shift = 1;
            } else {
                shift++;
            }
        }
        long[] c = new long[len];
        for (int j = 1; j <= len; j++) {
            c[j - 1] = Mod.sub(0, cur[j]);
        }
        return c;
    }

    /**
     * The n-th term (0-based) of the sequence with recurrence {@code c} and first terms {@code init}, computed as
     * x^n mod the characteristic polynomial.
     *
     * @throws IllegalArgumentException if n < 0 or init.length != c.length
     */
    public static long nthTerm(long[] c, long[] init, long n) {
        int order = c.length;
        if (n < 0 || init.length != order) {
            throw new IllegalArgumentException("need n >= 0 and init.length == c.length");
        }
        if (order == 0) {
            return 0;
        }
        if (n < order) {
            return Mod.norm(init[(int) n]);
        }
        long[] ch = new long[order + 1];
        ch[order] = 1;
        for (int j = 1; j <= order; j++) {
            ch[order - j] = Mod.sub(0, Mod.norm(c[j - 1]));
        }
        Poly charPoly = Poly.of(ch);
        Poly x = Poly.of(0, 1);
        Poly result = Poly.of(1);
        for (int bit = 63 - Long.numberOfLeadingZeros(n); bit >= 0; bit--) {
            result = result.mul(result).divMod(charPoly).r();
            if (((n >> bit) & 1) == 1) {
                result = result.mul(x).divMod(charPoly).r();
            }
        }
        long ans = 0;
        for (int i = 0; i < order; i++) {
            ans = Mod.add(ans, Mod.mul(result.coeff(i), Mod.norm(init[i])));
        }
        return ans;
    }

}
