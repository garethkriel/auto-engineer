package bench.segtree;

/** Lazy segment tree supporting range add, range sum and range min. */
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

    private void build(int node, int lo, int hi, long[] a) {
        if (hi - lo == 1) {
            sum[node] = a[lo];
            min[node] = a[lo];
            return;
        }
        int mid = (lo + hi) >>> 1;
        build(node * 2, lo, mid, a);
        build(node * 2 + 1, mid, hi, a);
        pull(node);
    }

    private void pull(int node) {
        sum[node] = sum[node * 2] + sum[node * 2 + 1];
        min[node] = Math.min(min[node * 2], min[node * 2 + 1]);
    }

    private void apply(int node, int len, long delta) {
        sum[node] += delta * len;
        min[node] += delta;
        lazy[node] += delta;
    }

    private void push(int node, int lo, int hi) {
        if (lazy[node] != 0) {
            int mid = (lo + hi) >>> 1;
            apply(node * 2, mid - lo, lazy[node]);
            apply(node * 2 + 1, hi - mid, lazy[node]);
            lazy[node] = 0;
        }
    }

    private void check(int from, int to) {
        if (from < 0 || to > n || from > to) {
            throw new IllegalArgumentException("bad range [" + from + ", " + to + ")");
        }
    }

    public void add(int from, int to, long delta) {
        check(from, to);
        if (from == to) {
            return;
        }
        add(1, 0, n, from, to, delta);
    }

    private void add(int node, int lo, int hi, int from, int to, long delta) {
        if (from <= lo && hi <= to) {
            apply(node, hi - lo, delta);
            return;
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        if (from < mid) {
            add(node * 2, lo, mid, from, to, delta);
        }
        if (to > mid) {
            add(node * 2 + 1, mid, hi, from, to, delta);
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

    private long sum(int node, int lo, int hi, int from, int to) {
        if (from <= lo && hi <= to) {
            return sum[node];
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        long r = 0;
        if (from < mid) {
            r += sum(node * 2, lo, mid, from, to);
        }
        if (to > mid) {
            r += sum(node * 2 + 1, mid, hi, from, to);
        }
        return r;
    }

    public long min(int from, int to) {
        check(from, to);
        if (from == to) {
            throw new IllegalArgumentException("empty range");
        }
        return min(1, 0, n, from, to);
    }

    private long min(int node, int lo, int hi, int from, int to) {
        if (from <= lo && hi <= to) {
            return min[node];
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        long r = Long.MAX_VALUE;
        if (from < mid) {
            r = Math.min(r, min(node * 2, lo, mid, from, to));
        }
        if (to > mid) {
            r = Math.min(r, min(node * 2 + 1, mid, hi, from, to));
        }
        return r;
    }

    public int size() {
        return n;
    }
}
