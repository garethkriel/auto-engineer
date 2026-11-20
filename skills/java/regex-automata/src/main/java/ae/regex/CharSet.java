package ae.regex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable set of UTF-16 code units stored as sorted, disjoint, non-adjacent inclusive ranges. */
public final class CharSet {
    /** Largest code unit. */
    public static final int MAX = 0xFFFF;
    public static final CharSet EMPTY = new CharSet(new int[0]);
    public static final CharSet ANY = new CharSet(new int[] {0, MAX});

    /** lo0, hi0, lo1, hi1, ... */
    private final int[] r;

    private CharSet(int[] r) {
        this.r = r;
    }

    public static CharSet of(int c) {
        return range(c, c);
    }

    public static CharSet range(int lo, int hi) {
        if (lo < 0 || hi > MAX || lo > hi) {
            throw new IllegalArgumentException("bad range " + lo + ".." + hi);
        }
        return new CharSet(new int[] {lo, hi});
    }

    private static CharSet normalise(List<int[]> parts) {
        parts.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] p : parts) {
            if (!merged.isEmpty() && p[0] <= merged.get(merged.size() - 1)[1] + 1) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], p[1]);
            } else {
                merged.add(new int[] {p[0], p[1]});
            }
        }
        int[] flat = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            flat[2 * i] = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return new CharSet(flat);
    }

    private List<int[]> parts() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < r.length; i += 2) {
            out.add(new int[] {r[i], r[i + 1]});
        }
        return out;
    }

    public CharSet union(CharSet o) {
        List<int[]> all = parts();
        all.addAll(o.parts());
        return normalise(all);
    }

    public CharSet negate() {
        List<int[]> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < r.length; i += 2) {
            if (r[i] > next) {
                out.add(new int[] {next, r[i] - 1});
            }
            next = r[i + 1] + 1;
        }
        if (next <= MAX) {
            out.add(new int[] {next, MAX});
        }
        return normalise(out);
    }

    public CharSet intersect(CharSet o) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < r.length && j < o.r.length) {
            int lo = Math.max(r[i], o.r[j]);
            int hi = Math.min(r[i + 1], o.r[j + 1]);
            if (lo <= hi) {
                out.add(new int[] {lo, hi});
            }
            if (r[i + 1] < o.r[j + 1]) {
                i += 2;
            } else {
                j += 2;
            }
        }
        return normalise(out);
    }

    public CharSet minus(CharSet o) {
        return intersect(o.negate());
    }

    public boolean contains(int c) {
        int lo = 0;
        int hi = r.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c < r[2 * mid]) {
                hi = mid - 1;
            } else if (c > r[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return r.length == 0;
    }

    public int size() {
        int n = 0;
        for (int i = 0; i < r.length; i += 2) {
            n += r[i + 1] - r[i] + 1;
        }
        return n;
    }

    public int rangeCount() {
        return r.length / 2;
    }

    /** Inclusive lower bound of range {@code i}. */
    public int lo(int i) {
        return r[2 * i];
    }

    /** Inclusive upper bound of range {@code i}. */
    public int hi(int i) {
        return r[2 * i + 1];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CharSet c && Arrays.equals(r, c.r);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(r);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < r.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("U+%04X", r[i]));
            if (r[i + 1] != r[i]) {
                sb.append(String.format("-U+%04X", r[i + 1]));
            }
        }
        return sb.append('}').toString();
    }
}
