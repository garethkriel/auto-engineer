package ae.netroute;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Longest-prefix-match routing table backed by a binary trie. */
public final class RouteTable {
    /** A route: prefix and next hop. */
    public record Route(Cidr cidr, String nextHop) { }

    private static final class Node {
        final Node[] child = new Node[2];
        String hop;

        boolean empty() {
            return hop == null && child[0] == null && child[1] == null;
        }
    }

    private Node root = new Node();
    private int size;

    private static int bit(long base, int depth) {
        return (int) ((base >>> (31 - depth)) & 1);
    }

    /** Adds or replaces a route; returns the previous next hop or null. */
    public String add(Cidr cidr, String nextHop) {
        Objects.requireNonNull(cidr);
        Objects.requireNonNull(nextHop);
        Node n = root;
        for (int d = 0; d < cidr.len(); d++) {
            int b = bit(cidr.base(), d);
            if (n.child[b] == null) n.child[b] = new Node();
            n = n.child[b];
        }
        String prev = n.hop;
        n.hop = nextHop;
        if (prev == null) size++;
        return prev;
    }

    /** Removes the route for exactly this prefix; prunes nodes that no longer lead anywhere. */
    public boolean remove(Cidr cidr) {
        Node[] path = new Node[cidr.len() + 1];
        Node n = root;
        path[0] = n;
        for (int d = 0; d < cidr.len(); d++) {
            n = n.child[bit(cidr.base(), d)];
            if (n == null) return false;
            path[d + 1] = n;
        }
        if (n.hop == null) return false;
        n.hop = null;
        size--;
        for (int d = cidr.len(); d > 0 && path[d].empty(); d--) {
            path[d - 1].child[bit(cidr.base(), d - 1)] = null;
        }
        return true;
    }

    public int size() {
        return size;
    }

    /** Number of trie nodes including the root (for tests of pruning). */
    int nodeCount() {
        return count(root);
    }

    private static int count(Node n) {
        return n == null ? 0 : 1 + count(n.child[0]) + count(n.child[1]);
    }

    /** Longest-prefix match. */
    public Optional<Route> lookup(long addr) {
        Node n = root;
        String hop = n.hop;
        int best = hop == null ? -1 : 0;
        for (int d = 0; d < 32 && n != null; d++) {
            n = n.child[bit(addr, d)];
            if (n != null && n.hop != null) {
                hop = n.hop;
                best = d + 1;
            }
        }
        if (best < 0) return Optional.empty();
        return Optional.of(new Route(Cidr.containing(addr, best), hop));
    }

    /** All routes ordered by (base, length). */
    public List<Route> routes() {
        List<Route> out = new ArrayList<>();
        collect(root, 0, 0, out);
        return out;
    }

    private static void collect(Node n, long base, int depth, List<Route> out) {
        if (n == null) return;
        if (n.hop != null) out.add(new Route(Cidr.of(base, depth), n.hop));
        collect(n.child[0], base, depth + 1, out);
        collect(n.child[1], base | (1L << (31 - depth)), depth + 1, out);
    }

    /**
     * A new table with identical forwarding but fewer routes: a route whose covering route has the same hop is
     * dropped, two sibling routes with the same hop become their parent, repeated until nothing changes.
     */
    public RouteTable aggregate() {
        RouteTable copy = new RouteTable();
        for (Route r : routes()) copy.add(r.cidr(), r.nextHop());
        int before;
        do {
            before = copy.size;
            copy.root = copy.simplify(copy.root, null);
            if (copy.root == null) copy.root = new Node();
        } while (copy.size < before);
        return copy;
    }

    /** Bottom-up pass; {@code inherited} is the hop an address would get from ancestors. Returns null if pruned. */
    private Node simplify(Node n, String inherited) {
        if (n == null) return null;
        String below = n.hop != null ? n.hop : inherited;
        n.child[0] = simplify(n.child[0], below);
        n.child[1] = simplify(n.child[1], below);
        Node a = n.child[0];
        Node b = n.child[1];
        if (a != null && b != null && a.hop != null && a.hop.equals(b.hop)) {
            if (n.hop == null) size++;
            n.hop = a.hop;
            a.hop = null;
            b.hop = null;
            size -= 2;
            n.child[0] = a.empty() ? null : a;
            n.child[1] = b.empty() ? null : b;
        }
        if (n.hop != null && n.hop.equals(inherited)) {
            n.hop = null;
            size--;
        }
        return n.empty() && n != root ? null : n;
    }

    /**
     * True if every address has the same next hop (or none) in both tables. Lookup is piecewise constant with
     * breakpoints at route starts and ends, so checking those points is exact.
     */
    public boolean equivalentTo(RouteTable other) {
        TreeSet<Long> points = new TreeSet<>();
        points.add(0L);
        for (RouteTable t : List.of(this, other)) {
            for (Route r : t.routes()) {
                points.add(r.cidr().first());
                if (r.cidr().last() < Ip4.MAX) points.add(r.cidr().last() + 1);
            }
        }
        for (long p : points) {
            if (!Objects.equals(hopAt(p), other.hopAt(p))) return false;
        }
        return true;
    }

    private String hopAt(long addr) {
        return lookup(addr).map(Route::nextHop).orElse(null);
    }
}
