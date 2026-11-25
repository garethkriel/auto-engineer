package ae.life;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * HashLife universe (rule B3/S23): a hash-consed quadtree plus memoised "centre half after 2^j generations"
 * results (Gosper). Coordinates: x east, y south, origin at the root centre. Population uses exact arithmetic and
 * throws {@link ArithmeticException} if it ever overflows a long.
 */
public final class Universe {
    private static final int MAX_LEVEL = 60;

    private record Key(Node nw, Node ne, Node sw, Node se) { }

    private Map<Key, Node> table = new HashMap<>();
    private final List<Node> empties = new ArrayList<>();
    private Node root;
    private long generation;

    public Universe() {
        empties.add(Node.DEAD);
        root = emptyNode(3);
    }

    public static Universe of(Collection<Cell> cells) {
        Universe u = new Universe();
        for (Cell c : cells) {
            u.set(c.x(), c.y(), true);
        }
        return u;
    }

    // ---------------------------------------------------------------- node construction

    private Node join(Node nw, Node ne, Node sw, Node se) {
        Key key = new Key(nw, ne, sw, se);
        Node found = table.get(key);
        if (found != null) {
            return found;
        }
        long pop = Math.addExact(Math.addExact(nw.pop, ne.pop), Math.addExact(sw.pop, se.pop));
        Node n = new Node(nw.level + 1, nw, ne, sw, se, pop);
        table.put(key, n);
        return n;
    }

    private Node emptyNode(int level) {
        while (empties.size() <= level) {
            Node e = empties.get(empties.size() - 1);
            empties.add(join(e, e, e, e));
        }
        return empties.get(level);
    }

    private Node centre(Node n) {
        return join(n.nw.se, n.ne.sw, n.sw.ne, n.se.nw);
    }

    private Node centreH(Node west, Node east) {
        return join(west.ne, east.nw, west.se, east.sw);
    }

    private Node centreV(Node north, Node south) {
        return join(north.sw, north.se, south.nw, south.ne);
    }

    // ---------------------------------------------------------------- the algorithm

    /** Centre half of {@code n} (level >= 2) after 2^j generations, 0 <= j <= level-2. */
    private Node advance(Node n, int j) {
        if (n.pop == 0) {
            return emptyNode(n.level - 1);
        }
        Node[] memo = n.memoFor(j);
        Node cached = memo[j];
        if (cached != null) {
            return cached;
        }
        Node result;
        if (n.level == 2) {
            result = base(n);
        } else {
            Node n00 = n.nw;
            Node n01 = centreH(n.nw, n.ne);
            Node n02 = n.ne;
            Node n10 = centreV(n.nw, n.sw);
            Node n11 = centre(n);
            Node n12 = centreV(n.ne, n.se);
            Node n20 = n.sw;
            Node n21 = centreH(n.sw, n.se);
            Node n22 = n.se;
            Node a;
            Node b;
            Node c;
            Node d;
            if (j == n.level - 2) {
                // two full-speed phases of 2^(level-3) generations each
                int h = n.level - 3;
                Node r00 = advance(n00, h);
                Node r01 = advance(n01, h);
                Node r02 = advance(n02, h);
                Node r10 = advance(n10, h);
                Node r11 = advance(n11, h);
                Node r12 = advance(n12, h);
                Node r20 = advance(n20, h);
                Node r21 = advance(n21, h);
                Node r22 = advance(n22, h);
                a = advance(join(r00, r01, r10, r11), h);
                b = advance(join(r01, r02, r11, r12), h);
                c = advance(join(r10, r11, r20, r21), h);
                d = advance(join(r11, r12, r21, r22), h);
            } else {
                // slower: centre the nine overlapping nodes without advancing, then advance by 2^j once
                Node c00 = centre(n00);
                Node c01 = centre(n01);
                Node c02 = centre(n02);
                Node c10 = centre(n10);
                Node c11 = centre(n11);
                Node c12 = centre(n12);
                Node c20 = centre(n20);
                Node c21 = centre(n21);
                Node c22 = centre(n22);
                a = advance(join(c00, c01, c10, c11), j);
                b = advance(join(c01, c02, c11, c12), j);
                c = advance(join(c10, c11, c20, c21), j);
                d = advance(join(c11, c12, c21, c22), j);
            }
            result = join(a, b, c, d);
        }
        memo[j] = result;
        return result;
    }

    private static boolean alive(Node n, int x, int y) {
        // 4x4 node: x,y in 0..3, origin at north-west
        Node q = y < 2 ? (x < 2 ? n.nw : n.ne) : (x < 2 ? n.sw : n.se);
        Node leaf = (y % 2 == 0) ? ((x % 2 == 0) ? q.nw : q.ne) : ((x % 2 == 0) ? q.sw : q.se);
        return leaf.pop == 1;
    }

    /** One generation of a level-2 node: the centre 2x2. */
    private Node base(Node n) {
        Node[] out = new Node[4];
        int idx = 0;
        for (int y = 1; y <= 2; y++) {
            for (int x = 1; x <= 2; x++) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx != 0 || dy != 0) && alive(n, x + dx, y + dy)) {
                            count++;
                        }
                    }
                }
                boolean self = alive(n, x, y);
                boolean next = count == 3 || (count == 2 && self);
                out[idx++] = next ? Node.ALIVE : Node.DEAD;
            }
        }
        return join(out[0], out[1], out[2], out[3]);
    }

    // ---------------------------------------------------------------- root management

    private void expand() {
        if (root.level >= MAX_LEVEL) {
            throw new ArithmeticException("universe too large");
        }
        Node e = emptyNode(root.level - 1);
        root = join(join(e, e, e, root.nw), join(e, e, root.ne, e), join(e, root.sw, e, e), join(root.se, e, e, e));
    }

    /** Returns the quadrant of {@code n}: 0 nw, 1 ne, 2 sw, 3 se. */
    private static Node quadrant(Node n, int q) {
        return switch (q) {
            case 0 -> n.nw;
            case 1 -> n.ne;
            case 2 -> n.sw;
            default -> n.se;
        };
    }

    /** All live cells lie in the centre quarter: each child holds only its inner quadrant, recursively once. */
    private boolean padded() {
        for (int q = 0; q < 4; q++) {
            Node child = quadrant(root, q);
            Node inner = quadrant(child, 3 - q);
            if (child.pop != inner.pop) {
                return false;
            }
            Node innerOfInner = quadrant(inner, 3 - q);
            if (inner.pop != innerOfInner.pop) {
                return false;
            }
        }
        return true;
    }

    /** Advances {@code n >= 0} generations. */
    public void step(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        for (int j = 0; j < 63; j++) {
            if ((n >>> j & 1L) == 0) {
                continue;
            }
            while (root.level < Math.max(3, j + 2) || !padded()) {
                expand();
            }
            root = advance(root, j);
            generation += 1L << j;
        }
    }

    // ---------------------------------------------------------------- cells

    private static long half(int level) {
        return 1L << (level - 1);
    }

    private boolean inside(long x, long y) {
        long h = half(root.level);
        return x >= -h && x < h && y >= -h && y < h;
    }

    public boolean get(long x, long y) {
        if (!inside(x, y)) {
            return false;
        }
        Node n = root;
        long cx = x;
        long cy = y;
        while (n.level > 1) {
            long q = 1L << (n.level - 2);
            int quad = (cy < 0 ? 0 : 2) + (cx < 0 ? 0 : 1);
            cx += cx < 0 ? q : -q;
            cy += cy < 0 ? q : -q;
            n = quadrant(n, quad);
        }
        return quadrant(n, (cy < 0 ? 0 : 2) + (cx < 0 ? 0 : 1)).pop == 1;
    }

    public void set(long x, long y, boolean value) {
        if (!value && !inside(x, y)) {
            return;
        }
        while (!inside(x, y)) {
            expand();
        }
        root = setRec(root, x, y, value);
    }

    private Node setRec(Node n, long x, long y, boolean value) {
        int quad = (y < 0 ? 0 : 2) + (x < 0 ? 0 : 1);
        Node child = quadrant(n, quad);
        Node replaced;
        if (n.level == 1) {
            replaced = value ? Node.ALIVE : Node.DEAD;
        } else {
            long q = 1L << (n.level - 2);
            replaced = setRec(child, x + (x < 0 ? q : -q), y + (y < 0 ? q : -q), value);
        }
        return switch (quad) {
            case 0 -> join(replaced, n.ne, n.sw, n.se);
            case 1 -> join(n.nw, replaced, n.sw, n.se);
            case 2 -> join(n.nw, n.ne, replaced, n.se);
            default -> join(n.nw, n.ne, n.sw, replaced);
        };
    }

    public long population() {
        return root.pop;
    }

    public long generation() {
        return generation;
    }

    public int level() {
        return root.level;
    }

    /** Number of distinct internal nodes in the table (leaves not counted). */
    public int nodeCount() {
        return table.size();
    }

    /** Live cells ordered by row, then column. */
    public List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        collect(root, 0, 0, out);
        out.sort(null);
        return out;
    }

    private static void collect(Node n, long cx, long cy, List<Cell> out) {
        if (n.pop == 0) {
            return;
        }
        if (n.level == 1) {
            if (n.nw.pop == 1) {
                out.add(new Cell(cx - 1, cy - 1));
            }
            if (n.ne.pop == 1) {
                out.add(new Cell(cx, cy - 1));
            }
            if (n.sw.pop == 1) {
                out.add(new Cell(cx - 1, cy));
            }
            if (n.se.pop == 1) {
                out.add(new Cell(cx, cy));
            }
            return;
        }
        long q = 1L << (n.level - 2);
        collect(n.nw, cx - q, cy - q, out);
        collect(n.ne, cx + q, cy - q, out);
        collect(n.sw, cx - q, cy + q, out);
        collect(n.se, cx + q, cy + q, out);
    }

    // ---------------------------------------------------------------- maintenance

    /** Rebuilds the table so that it holds only nodes reachable from the root; memoised results are dropped. */
    public void compact() {
        Map<Key, Node> old = table;
        table = new HashMap<>();
        empties.clear();
        empties.add(Node.DEAD);
        Map<Node, Node> copy = new IdentityHashMap<>();
        root = rebuild(root, copy);
        old.clear();
    }

    private Node rebuild(Node n, Map<Node, Node> copy) {
        if (n.level == 0) {
            return n;
        }
        Node done = copy.get(n);
        if (done != null) {
            return done;
        }
        Node r = join(rebuild(n.nw, copy), rebuild(n.ne, copy), rebuild(n.sw, copy), rebuild(n.se, copy));
        copy.put(n, r);
        return r;
    }

    /** Verifies every node reachable from the root; throws {@link IllegalStateException} on the first violation. */
    public void checkInvariants() {
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        check(root, seen);
    }

    private void check(Node n, Map<Node, Boolean> seen) {
        if (n.level == 0) {
            if (n != Node.DEAD && n != Node.ALIVE) {
                throw new IllegalStateException("leaf is not canonical");
            }
            return;
        }
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        Node.checkLocal(n);
        if (table.get(new Key(n.nw, n.ne, n.sw, n.se)) != n) {
            throw new IllegalStateException("canonicality violated: node at level " + n.level + " is not interned");
        }
        check(n.nw, seen);
        check(n.ne, seen);
        check(n.sw, seen);
        check(n.se, seen);
    }

    Node rootForTest() {
        return root;
    }

    void replaceRootUnchecked(Node n) {
        root = n;
    }
}
