package ae.netroute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable set of IPv4 addresses stored as sorted, disjoint, non-adjacent inclusive ranges. */
public final class AddrSet {
    public static final AddrSet EMPTY = new AddrSet(new long[0]);

    private final long[] r; // lo0, hi0, lo1, hi1, ...

    private AddrSet(long[] r) {
        this.r = r;
    }

    public static AddrSet of(long lo, long hi) {
        if (lo < 0 || hi > Ip4.MAX || lo > hi) throw new IllegalArgumentException("bad range " + lo + ".." + hi);
        return new AddrSet(new long[] {lo, hi});
    }

    public static AddrSet of(Cidr c) {
        return of(c.first(), c.last());
    }

    /** Sorts and merges overlapping or adjacent ranges. */
    private static AddrSet normalise(List<long[]> ranges) {
        List<long[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] out = new long[sorted.size() * 2];
        int n = 0;
        for (long[] x : sorted) {
            if (n > 0 && x[0] <= out[n - 1] + 1) {
                out[n - 1] = Math.max(out[n - 1], x[1]);
            } else {
                out[n++] = x[0];
                out[n++] = x[1];
            }
        }
        return new AddrSet(Arrays.copyOf(out, n));
    }

    public AddrSet union(AddrSet o) {
        List<long[]> all = new ArrayList<>(ranges());
        all.addAll(o.ranges());
        return normalise(all);
    }

    public AddrSet complement() {
        List<long[]> out = new ArrayList<>();
        long next = 0;
        for (int i = 0; i < r.length; i += 2) {
            if (r[i] > next) out.add(new long[] {next, r[i] - 1});
            next = r[i + 1] + 1;
        }
        if (next <= Ip4.MAX) out.add(new long[] {next, Ip4.MAX});
        return normalise(out);
    }

    public AddrSet intersect(AddrSet o) {
        return complement().union(o.complement()).complement();
    }

    public AddrSet subtract(AddrSet o) {
        return intersect(o.complement());
    }

    public boolean contains(long addr) {
        int lo = 0, hi = r.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (addr < r[2 * mid]) hi = mid - 1;
            else if (addr > r[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    public long size() {
        long s = 0;
        for (int i = 0; i < r.length; i += 2) s += r[i + 1] - r[i] + 1;
        return s;
    }

    public boolean isEmpty() {
        return r.length == 0;
    }

    /** Copies of the {@code [lo, hi]} ranges, ascending. */
    public List<long[]> ranges() {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < r.length; i += 2) out.add(new long[] {r[i], r[i + 1]});
        return out;
    }

    /** The minimal list of CIDR blocks covering exactly this set, ascending. */
    public List<Cidr> cidrs() {
        List<Cidr> out = new ArrayList<>();
        for (int i = 0; i < r.length; i += 2) {
            long lo = r[i];
            long hi = r[i + 1];
            while (lo <= hi) {
                // largest block aligned at lo: lowest set bit of lo (the whole space when lo == 0)
                int hostBits = lo == 0 ? 32 : Long.numberOfTrailingZeros(lo);
                while (lo + (1L << hostBits) - 1 > hi) hostBits--;
                out.add(Cidr.of(lo, 32 - hostBits));
                lo += 1L << hostBits;
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AddrSet s && Arrays.equals(r, s.r);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(r);
    }

    @Override
    public String toString() {
        return cidrs().toString();
    }
}
