package bench.segtree;

/** Array supporting range add, range sum and range min in O(log n) using a lazy segment tree. */
public final class RangeArray {
    private final int n;
    private final long[] sum;
    private final long[] min;
    private final long[] lazy;

    public RangeArray(long[] initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial is null");
        }
        this.n = initial.length;
        int nodes = n == 0 ? 1 : 2 * n - 1;
        sum = new long[nodes];
        min = new long[nodes];
        lazy = new long[nodes];
        if (n > 0) {
            build(0, 0, n - 1, initial.clone());
        }
    }

    // Node layout: left child = node + 1, right child = node + 2 * (mid - l + 1).
    private void build(int node, int l, int r, long[] a) {
        if (l == r) {
            sum[node] = a[l];
            min[node] = a[l];
            return;
        }
        int mid = (l + r) >>> 1;
        int left = node + 1;
        int right = node + 2 * (mid - l + 1);
        build(left, l, mid, a);
        build(right, mid + 1, r, a);
        pull(node, left, right);
    }

    private void pull(int node, int left, int right) {
        sum[node] = sum[left] + sum[right];
        min[node] = Math.min(min[left], min[right]);
    }

    private void apply(int node, int len, long delta) {
        sum[node] += delta * len;
        min[node] += delta;
        lazy[node] += delta;
    }

    private void push(int node, int l, int mid, int r) {
        long d = lazy[node];
        if (d != 0) {
            apply(node + 1, mid - l + 1, d);
            apply(node + 2 * (mid - l + 1), r - mid, d);
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
        add(0, 0, n - 1, from, to - 1, delta);
    }

    private void add(int node, int l, int r, int ql, int qr, long delta) {
        if (ql <= l && r <= qr) {
            apply(node, r - l + 1, delta);
            return;
        }
        int mid = (l + r) >>> 1;
        push(node, l, mid, r);
        int left = node + 1;
        int right = node + 2 * (mid - l + 1);
        if (ql <= mid) {
            add(left, l, mid, ql, qr, delta);
        }
        if (qr > mid) {
            add(right, mid + 1, r, ql, qr, delta);
        }
        pull(node, left, right);
    }

    public long sum(int from, int to) {
        check(from, to);
        if (from == to) {
            return 0;
        }
        return sum(0, 0, n - 1, from, to - 1);
    }

    private long sum(int node, int l, int r, int ql, int qr) {
        if (ql <= l && r <= qr) {
            return sum[node];
        }
        int mid = (l + r) >>> 1;
        push(node, l, mid, r);
        long res = 0;
        if (ql <= mid) {
            res += sum(node + 1, l, mid, ql, qr);
        }
        if (qr > mid) {
            res += sum(node + 2 * (mid - l + 1), mid + 1, r, ql, qr);
        }
        return res;
    }

    public long min(int from, int to) {
        check(from, to);
        if (from == to) {
            throw new IllegalArgumentException("min of empty range");
        }
        return min(0, 0, n - 1, from, to - 1);
    }

    private long min(int node, int l, int r, int ql, int qr) {
        if (ql <= l && r <= qr) {
            return min[node];
        }
        int mid = (l + r) >>> 1;
        push(node, l, mid, r);
        long res = Long.MAX_VALUE;
        if (ql <= mid) {
            res = Math.min(res, min(node + 1, l, mid, ql, qr));
        }
        if (qr > mid) {
            res = Math.min(res, min(node + 2 * (mid - l + 1), mid + 1, r, ql, qr));
        }
        return res;
    }

    public int size() {
        return n;
    }
}
