package ae.life;

/**
 * Immutable quadtree node. A node of level k covers 2^k x 2^k cells. Level 0 nodes are the two shared leaves
 * {@link #DEAD} and {@link #ALIVE}. {@code memo[j]} caches the centre half of this node after 2^j generations.
 */
final class Node {
    static final Node DEAD = new Node(0, null, null, null, null, 0);
    static final Node ALIVE = new Node(0, null, null, null, null, 1);

    final int level;
    final Node nw;
    final Node ne;
    final Node sw;
    final Node se;
    final long pop;
    private Node[] memo;

    Node(int level, Node nw, Node ne, Node sw, Node se, long pop) {
        this.level = level;
        this.nw = nw;
        this.ne = ne;
        this.sw = sw;
        this.se = se;
        this.pop = pop;
    }

    /** The memo array (index j = 0..level-2), created on first use. */
    Node[] memoFor(int minLevelCheck) {
        if (memo == null) {
            memo = new Node[Math.max(level - 1, 1)];
        }
        return memo;
    }

    boolean hasMemo() {
        return memo != null;
    }

    Node memoAt(int j) {
        return memo == null ? null : memo[j];
    }

    /** Checks the local rules of this node only (not canonicality); throws {@link IllegalStateException}. */
    static void checkLocal(Node n) {
        if (n.level == 0) {
            if (n.nw != null || n.ne != null || n.sw != null || n.se != null) {
                throw new IllegalStateException("leaf has children");
            }
            if (n.pop != 0 && n.pop != 1) {
                throw new IllegalStateException("population of leaf is " + n.pop);
            }
            return;
        }
        Node[] kids = {n.nw, n.ne, n.sw, n.se};
        long sum = 0;
        for (Node k : kids) {
            if (k == null || k.level != n.level - 1) {
                throw new IllegalStateException("level mismatch at level " + n.level);
            }
            sum += k.pop;
        }
        if (sum != n.pop) {
            throw new IllegalStateException("population mismatch at level " + n.level + ": " + n.pop + " vs " + sum);
        }
        if (n.memo != null) {
            for (int j = 0; j < n.memo.length; j++) {
                Node r = n.memo[j];
                if (r != null && r.level != n.level - 1) {
                    throw new IllegalStateException("memo " + j + " has level " + r.level + " at level " + n.level);
                }
            }
        }
    }
}
