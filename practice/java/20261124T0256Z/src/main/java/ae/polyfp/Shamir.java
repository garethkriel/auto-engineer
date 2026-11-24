package ae.polyfp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Shamir (k, n) threshold secret sharing over GF(p). */
public final class Shamir {
    private Shamir() {
    }

    public static List<Share> split(long p, long secret, int k, int n, Random random) {
        Objects.requireNonNull(random, "random");
        Poly.requirePrime(p);
        if (secret < 0 || secret >= p) {
            throw new IllegalArgumentException("secret out of range");
        }
        if (k < 1 || k > n || n >= p) {
            throw new IllegalArgumentException("need 1 <= k <= n < p");
        }
        long[] c = new long[k];
        c[0] = secret;
        for (int i = 1; i < k; i++) {
            c[i] = random.nextLong(p);
        }
        Poly f = Poly.of(p, c);
        List<Share> out = new ArrayList<>(n);
        for (int x = 1; x <= n; x++) {
            out.add(new Share(x, f.eval(x)));
        }
        return out;
    }

    public static long combine(long p, List<Share> shares) {
        Objects.requireNonNull(shares, "shares");
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("no shares");
        }
        Set<Long> seen = new HashSet<>();
        long[] xs = new long[shares.size()];
        long[] ys = new long[shares.size()];
        for (int i = 0; i < xs.length; i++) {
            Share s = shares.get(i);
            if (!seen.add(s.x())) {
                throw new IllegalArgumentException("duplicate x " + s.x());
            }
            xs[i] = s.x();
            ys[i] = s.y();
        }
        return Poly.interpolate(p, xs, ys).eval(0);
    }
}
