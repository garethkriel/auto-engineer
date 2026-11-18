package ae.dsu;

/**
 * Disjoint-set union with undo. Uses union by size and no path compression, so every
 * successful {@link #union} is one reversible step costing O(log n) to find.
 */
public final class RollbackDsu {
    private final int[] parent;
    private final int[] size;
    /** Stack of child roots that were attached; the parent is {@code parent[child]}. */
    private final int[] history;
    private int top;
    private int components;

    public RollbackDsu(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        parent = new int[n];
        size = new int[n];
        history = new int[Math.max(0, n - 1)];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            size[i] = 1;
        }
        components = n;
    }

    private void check(int x) {
        if (x < 0 || x >= parent.length) {
            throw new IllegalArgumentException("element out of range: " + x);
        }
    }

    public int find(int x) {
        check(x);
        while (parent[x] != x) {
            x = parent[x];
        }
        return x;
    }

    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }

    public int componentSize(int x) {
        return size[find(x)];
    }

    public int components() {
        return components;
    }

    /** Merges the sets of a and b; returns true only if they were different (and records the step). */
    public boolean union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return false;
        }
        if (size[ra] < size[rb]) {
            int t = ra;
            ra = rb;
            rb = t;
        }
        parent[rb] = ra;
        size[ra] += size[rb];
        history[top++] = rb;
        components--;
        return true;
    }

    /** Marker for {@link #rollbackTo}: the number of merges recorded so far. */
    public int snapshot() {
        return top;
    }

    /** Undoes the most recent merge. */
    public void undo() {
        if (top == 0) {
            throw new IllegalStateException("nothing to undo");
        }
        int child = history[--top];
        int root = parent[child];
        size[root] -= size[child];
        parent[child] = child;
        components++;
    }

    public void rollbackTo(int snapshot) {
        if (snapshot < 0 || snapshot > top) {
            throw new IllegalArgumentException("bad snapshot " + snapshot + " (current " + top + ")");
        }
        while (top > snapshot) {
            undo();
        }
    }
}
