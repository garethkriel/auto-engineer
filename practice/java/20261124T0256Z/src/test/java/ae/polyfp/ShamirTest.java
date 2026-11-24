package ae.polyfp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShamirTest {

    static void subsets(int n, int k, int start, List<Integer> cur, List<List<Integer>> out) {
        if (cur.size() == k) {
            out.add(new ArrayList<>(cur));
            return;
        }
        for (int i = start; i < n; i++) {
            cur.add(i);
            subsets(n, k, i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    @Test
    void everyKSubsetReconstructs() {
        long p = 101;
        List<Share> shares = Shamir.split(p, 42, 3, 6, new Random(1));
        assertEquals(6, shares.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 1, shares.get(i).x());
            assertTrue(shares.get(i).y() >= 0 && shares.get(i).y() < p);
        }
        List<List<Integer>> subs = new ArrayList<>();
        subsets(6, 3, 0, new ArrayList<>(), subs);
        assertEquals(20, subs.size());
        for (List<Integer> s : subs) {
            List<Share> pick = new ArrayList<>();
            for (int i : s) {
                pick.add(shares.get(i));
            }
            assertEquals(42, Shamir.combine(p, pick), s.toString());
        }
        assertEquals(42, Shamir.combine(p, shares));
    }

    @Test
    void edgeThresholds() {
        long p = 13;
        assertEquals(0, Shamir.combine(p, Shamir.split(p, 0, 1, 1, new Random(2))));
        for (Share s : Shamir.split(p, 9, 1, 5, new Random(3))) {
            assertEquals(9, s.y(), "k=1 means constant polynomial");
        }
        List<Share> all = Shamir.split(p, 12, 12, 12, new Random(4));
        assertEquals(12, Shamir.combine(p, all));
    }

    @Test
    void fewerThanKSharesRevealNothing() {
        long p = 7;
        int k = 3;
        List<Share> shares = Shamir.split(p, 5, k, 5, new Random(9));
        List<Share> two = shares.subList(0, k - 1);
        long[] xs = new long[k];
        long[] ys = new long[k];
        for (int i = 0; i < k - 1; i++) {
            xs[i] = two.get(i).x();
            ys[i] = two.get(i).y();
        }
        xs[k - 1] = 0;
        for (long candidate = 0; candidate < p; candidate++) {
            ys[k - 1] = candidate;
            Poly f = Poly.interpolate(p, xs, ys);
            assertTrue(f.degree() <= k - 1);
            assertEquals(candidate, f.eval(0));
            assertEquals(two.get(0).y(), f.eval(two.get(0).x()));
            assertEquals(two.get(1).y(), f.eval(two.get(1).x()));
        }
    }

    @Test
    void sharesDifferAcrossSeedsButSecretStays() {
        long p = 2147483647L;
        List<Share> a = Shamir.split(p, 123456789, 4, 7, new Random(1));
        List<Share> b = Shamir.split(p, 123456789, 4, 7, new Random(2));
        assertNotEquals(a, b);
        assertEquals(123456789, Shamir.combine(p, a.subList(2, 6)));
        assertEquals(123456789, Shamir.combine(p, b.subList(1, 5)));
        assertEquals(a, Shamir.split(p, 123456789, 4, 7, new Random(1)), "deterministic for a seed");
    }

    @Test
    void argumentValidation() {
        Random r = new Random(0);
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(7, 7, 2, 3, r));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(7, -1, 2, 3, r));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(7, 1, 0, 3, r));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(7, 1, 4, 3, r));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(7, 1, 2, 7, r));
        assertThrows(IllegalArgumentException.class, () -> Shamir.combine(7, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Shamir.combine(7, List.of(new Share(1, 2), new Share(1, 3))));
        assertThrows(NullPointerException.class, () -> Shamir.split(7, 1, 2, 3, null));
    }
}
