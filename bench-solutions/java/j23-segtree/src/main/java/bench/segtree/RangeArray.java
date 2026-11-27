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
        int cap = Math.max(1, 4 * n);
        sum = new long[cap];
        min = new long[cap];
        lazy = new long[cap];
        if (n > 0) {
            build(1, 0, n, initial.clone());
        }
    }

    private void build(int node, int l, int r, long[] a) {
        if (r - l == 1) {
            sum[node] = a[l];
            min[node] = a[l];
            return;
        }
        int mid = (l + r) >>> 1;
        build(node * 2, l, mid, a);
        build(node * 2 + 1, mid, r, a);
        pull(node);
    }

    private void pull(int node) {
        sum[node] = sum[node * 2] + sum[node * 2 + 1];
        min[node] = Math.min(min[node * 2], min[node * 2 + 1]);
    }

    private void apply(int node, int len, long d) {
        sum[node] += d * len;
        min[node] += d;
        lazy[node] += d;
    }

    private void push(int node, int l, int r) {
        if (lazy[node] != 0) {
            int mid = (l + r) >>> 1;
            apply(node * 2, mid - l, lazy[node]);
            apply(node * 2 + 1, r - mid, lazy[node]);
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

    private void add(int node, int l, int r, int from, int to, long d) {
        if (from <= l && r <= to) {
            apply(node, r - l, d);
            return;
        }
        push(node, l, r);
        int mid = (l + r) >>> 1;
        if (from < mid) {
            add(node * 2, l, mid, from, to, d);
        }
        if (to > mid) {
            add(node * 2 + 1, mid, r, from, to, d);
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
        push(node, l, r);
        int mid = (l + r) >>> 1;
        long res = 0;
        if (from < mid) {
            res += sum(node * 2, l, mid, from, to);
        }
        if (to > mid) {
            res += sum(node * 2 + 1, mid, r, from, to);
        }
        return res;
    }

    public long min(int from, int to) {
        check(from, to);
        if (from == to) {
            throw new IllegalArgumentException("empty range");
        }
        return min(1, 0, n, from, to);
    }

    private long min(int node, int l, int r, int from, int to) {
        if (from <= l && r <= to) {
            return min[node];
        }
        push(node, l, r);
        int mid = (l + r) >>> 1;
        long res = Long.MAX_VALUE;
        if (from < mid) {
            res = Math.min(res, min(node * 2, l, mid, from, to));
        }
        if (to > mid) {
            res = Math.min(res, min(node * 2 + 1, mid, r, from, to));
        }
        return res;
    }

    public int size() {
        return n;
    }
}
