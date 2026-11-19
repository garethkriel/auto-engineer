package bench.segtree;

/** Array supporting range add, range sum and range min in O(log n) via a lazy segment tree. */
public final class RangeArray {
    private final int n;
    private final long[] sum;
    private final long[] min;
    private final long[] lazy;

    public RangeArray(long[] initial) {
        if (initial == null) {
            throw new IllegalArgumentException("null array");
        }
        n = initial.length;
        int size = Math.max(1, 4 * n);
        sum = new long[size];
        min = new long[size];
        lazy = new long[size];
        if (n > 0) {
            build(1, 0, n, initial);
        }
    }

    private void build(int node, int l, int r, long[] a) {
        if (r - l == 1) {
            sum[node] = a[l];
            min[node] = a[l];
            return;
        }
        int m = (l + r) >>> 1;
        build(2 * node, l, m, a);
        build(2 * node + 1, m, r, a);
        pull(node);
    }

    private void pull(int node) {
        sum[node] = sum[2 * node] + sum[2 * node + 1];
        min[node] = Math.min(min[2 * node], min[2 * node + 1]);
    }

    private void apply(int node, int len, long d) {
        sum[node] += d * len;
        min[node] += d;
        lazy[node] += d;
    }

    private void push(int node, int l, int m, int r) {
        long d = lazy[node];
        if (d != 0) {
            apply(2 * node, m - l, d);
            apply(2 * node + 1, r - m, d);
            lazy[node] = 0;
        }
    }

    private void check(int from, int to) {
        if (from < 0 || to > n || from > to) {
            throw new IllegalArgumentException("bad range [" + from + ", " + to + ") for size " + n);
        }
    }

    public void add(int from, int to, long delta) {
        check(from, to);
        if (from == to) {
            return;
        }
        add(1, 0, n, from, to, delta);
    }

    private void add(int node, int l, int r, int from, int to, long d) {
        if (from <= l && r <= to) {
            apply(node, r - l, d);
            return;
        }
        int m = (l + r) >>> 1;
        push(node, l, m, r);
        if (from < m) {
            add(2 * node, l, m, from, to, d);
        }
        if (to > m) {
            add(2 * node + 1, m, r, from, to, d);
        }
        pull(node);
    }

    public long sum(int from, int to) {
        check(from, to);
        if (from == to) {
            return 0;
        }
        return sum(1, 0, n, from, to);
    }

    private long sum(int node, int l, int r, int from, int to) {
        if (from <= l && r <= to) {
            return sum[node];
        }
        int m = (l + r) >>> 1;
        push(node, l, m, r);
        long s = 0;
        if (from < m) {
            s += sum(2 * node, l, m, from, to);
        }
        if (to > m) {
            s += sum(2 * node + 1, m, r, from, to);
        }
        return s;
    }

    public long min(int from, int to) {
        check(from, to);
        if (from == to) {
            throw new IllegalArgumentException("min of empty range");
        }
        return min(1, 0, n, from, to);
    }

    private long min(int node, int l, int r, int from, int to) {
        if (from <= l && r <= to) {
            return min[node];
        }
        int m = (l + r) >>> 1;
        push(node, l, m, r);
        long res = Long.MAX_VALUE;
        if (from < m) {
            res = Math.min(res, min(2 * node, l, m, from, to));
        }
        if (to > m) {
            res = Math.min(res, min(2 * node + 1, m, r, from, to));
        }
        return res;
    }

    public int size() {
        return n;
    }
}
