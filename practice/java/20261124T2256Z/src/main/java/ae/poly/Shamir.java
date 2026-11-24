package ae.poly;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Shamir (k, n) threshold secret sharing over {@link Mod#P}. */
public final class Shamir {
    /** One share: the value of the hidden polynomial at {@code x}. */
    public record Share(int x, long y) {}

    private Shamir() {}

    /**
     * Splits {@code secret} into n shares at x = 1..n of a polynomial of degree < k with constant term = secret.
     * The k-1 higher coefficients come from {@code coeffs} (reduced mod p); it is not called when k == 1.
     */
    public static List<Share> split(long secret, int k, int n, LongSupplier coeffs) {
        Objects.requireNonNull(coeffs, "coeffs");
        if (k < 1 || k > n || n >= Mod.P) {
            throw new IllegalArgumentException("need 1 <= k <= n < p");
        }
        if (secret < 0 || secret >= Mod.P) {
            throw new IllegalArgumentException("secret out of range");
        }
        long[] poly = new long[k];
        poly[0] = secret;
        for (int i = 1; i < k; i++) {
            poly[i] = Mod.norm(coeffs.getAsLong());
        }
        Poly f = Poly.of(poly);
        Share[] out = new Share[n];
        for (int x = 1; x <= n; x++) {
            out[x - 1] = new Share(x, f.eval(x));
        }
        return List.of(out);
    }

    private static void validate(List<Share> shares) {
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("no shares");
        }
        Set<Integer> seen = new HashSet<>();
        for (Share s : shares) {
            if (s.x() <= 0) {
                throw new IllegalArgumentException("share x must be positive: " + s.x());
            }
            if (s.y() < 0 || s.y() >= Mod.P) {
                throw new IllegalArgumentException("share y out of range");
            }
            if (!seen.add(s.x())) {
                throw new IllegalArgumentException("duplicate share x: " + s.x());
            }
        }
    }

    /** Lagrange interpolation at 0 through all given shares. */
    public static long recover(List<Share> shares) {
        validate(shares);
        long total = 0;
        for (Share si : shares) {
            long num = 1;
            long den = 1;
            for (Share sj : shares) {
                if (sj != si) {
                    num = Mod.mul(num, sj.x());
                    den = Mod.mul(den, Mod.sub(sj.x(), si.x()));
                }
            }
            total = Mod.add(total, Mod.mul(si.y(), Mod.mul(num, Mod.inv(den))));
        }
        return total;
    }

    /** True iff all shares lie on one polynomial of degree < k. */
    public static boolean isConsistent(List<Share> shares, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be positive");
        }
        validate(shares);
        long[] xs = new long[shares.size()];
        long[] ys = new long[shares.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = shares.get(i).x();
            ys[i] = shares.get(i).y();
        }
        return Poly.interpolate(xs, ys).degree() < k;
    }
}
