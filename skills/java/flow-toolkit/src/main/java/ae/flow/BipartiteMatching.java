package ae.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Hopcroft-Karp maximum bipartite matching with König's minimum vertex cover. All traversals are iterative. */
public final class BipartiteMatching {

    /** Minimum vertex cover: its size equals the maximum matching size. */
    public record Cover(Set<Integer> left, Set<Integer> right) {}

    private static final int INF = Integer.MAX_VALUE;

    private final int leftSize;
    private final int rightSize;
    private final List<int[]> edges = new ArrayList<>();
    private int[] start;
    private int[] adj;
    private int[] matchL;
    private int[] matchR;
    private boolean solved;

    public BipartiteMatching(int leftSize, int rightSize) {
        if (leftSize < 0 || rightSize < 0) {
            throw new IllegalArgumentException("sizes must be >= 0");
        }
        this.leftSize = leftSize;
        this.rightSize = rightSize;
    }

    public void addEdge(int l, int r) {
        if (l < 0 || l >= leftSize || r < 0 || r >= rightSize) {
            throw new IllegalArgumentException("edge out of range: " + l + "-" + r);
        }
        edges.add(new int[] {l, r});
        solved = false;
    }

    /** Computes (from scratch) and returns the size of a maximum matching. */
    public int solve() {
        start = new int[leftSize + 1];
        for (int[] e : edges) {
            start[e[0] + 1]++;
        }
        for (int i = 0; i < leftSize; i++) {
            start[i + 1] += start[i];
        }
        adj = new int[edges.size()];
        int[] fill = Arrays.copyOf(start, leftSize);
        for (int[] e : edges) {
            adj[fill[e[0]]++] = e[1];
        }
        matchL = new int[leftSize];
        matchR = new int[rightSize];
        Arrays.fill(matchL, -1);
        Arrays.fill(matchR, -1);
        int[] dist = new int[leftSize];
        int[] ptr = new int[leftSize];
        int[] stack = new int[leftSize + 1];
        int size = 0;
        while (bfs(dist)) {
            System.arraycopy(start, 0, ptr, 0, leftSize);
            for (int l = 0; l < leftSize; l++) {
                if (matchL[l] < 0 && augment(l, dist, ptr, stack)) {
                    size++;
                }
            }
        }
        solved = true;
        return size;
    }

    private boolean bfs(int[] dist) {
        int[] queue = new int[leftSize];
        int qh = 0;
        int qt = 0;
        for (int l = 0; l < leftSize; l++) {
            if (matchL[l] < 0) {
                dist[l] = 0;
                queue[qt++] = l;
            } else {
                dist[l] = INF;
            }
        }
        boolean found = false;
        while (qh < qt) {
            int u = queue[qh++];
            for (int i = start[u]; i < start[u + 1]; i++) {
                int w = matchR[adj[i]];
                if (w < 0) {
                    found = true;
                } else if (dist[w] == INF) {
                    dist[w] = dist[u] + 1;
                    queue[qt++] = w;
                }
            }
        }
        return found;
    }

    private boolean augment(int root, int[] dist, int[] ptr, int[] stack) {
        int sp = 0;
        stack[sp++] = root;
        while (sp > 0) {
            int u = stack[sp - 1];
            if (ptr[u] == start[u + 1]) {
                dist[u] = INF;
                sp--;
                if (sp > 0) {
                    ptr[stack[sp - 1]]++;
                }
                continue;
            }
            int r = adj[ptr[u]];
            int w = matchR[r];
            if (w < 0) {
                for (int i = sp - 1; i >= 0; i--) {
                    int lu = stack[i];
                    int rr = adj[ptr[lu]];
                    matchL[lu] = rr;
                    matchR[rr] = lu;
                }
                return true;
            }
            if (dist[w] != INF && dist[w] == dist[u] + 1) {
                stack[sp++] = w;
            } else {
                ptr[u]++;
            }
        }
        return false;
    }

    private void requireSolved() {
        if (!solved) {
            throw new IllegalStateException("call solve() first");
        }
    }

    /** Right partner of {@code l}, or -1. */
    public int matchOfLeft(int l) {
        requireSolved();
        if (l < 0 || l >= leftSize) {
            throw new IllegalArgumentException("left out of range: " + l);
        }
        return matchL[l];
    }

    /** Left partner of {@code r}, or -1. */
    public int matchOfRight(int r) {
        requireSolved();
        if (r < 0 || r >= rightSize) {
            throw new IllegalArgumentException("right out of range: " + r);
        }
        return matchR[r];
    }

    /** König's theorem: left vertices not reachable by alternating paths plus right vertices that are. */
    public Cover minVertexCover() {
        requireSolved();
        boolean[] seenL = new boolean[leftSize];
        boolean[] seenR = new boolean[rightSize];
        int[] queue = new int[leftSize];
        int qh = 0;
        int qt = 0;
        for (int l = 0; l < leftSize; l++) {
            if (matchL[l] < 0) {
                seenL[l] = true;
                queue[qt++] = l;
            }
        }
        while (qh < qt) {
            int u = queue[qh++];
            for (int i = start[u]; i < start[u + 1]; i++) {
                int r = adj[i];
                if (!seenR[r]) {
                    seenR[r] = true;
                    int w = matchR[r];
                    if (w >= 0 && !seenL[w]) {
                        seenL[w] = true;
                        queue[qt++] = w;
                    }
                }
            }
        }
        Set<Integer> left = new TreeSet<>();
        Set<Integer> right = new TreeSet<>();
        for (int l = 0; l < leftSize; l++) {
            if (!seenL[l]) {
                left.add(l);
            }
        }
        for (int r = 0; r < rightSize; r++) {
            if (seenR[r]) {
                right.add(r);
            }
        }
        return new Cover(left, right);
    }
}
