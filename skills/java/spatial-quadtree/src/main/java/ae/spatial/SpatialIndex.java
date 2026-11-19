package ae.spatial;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.CRC32;

/** A point quadtree keyed by string id with range, radius, k-nearest, polygon and DBSCAN queries. */
public final class SpatialIndex {
    static final int CAPACITY = 8;
    static final int MAX_DEPTH = 32;
    private static final byte[] MAGIC = "SPX1".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN_ENTRY_BYTES = 4 + 1 + 16;

    private static final class Node {
        final double minX;
        final double minY;
        final double maxX;
        final double maxY;
        final int depth;
        List<Entry> items = new ArrayList<>();
        Node[] kids;
        int count;

        Node(double minX, double minY, double maxX, double maxY, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.depth = depth;
        }

        boolean leaf() {
            return kids == null;
        }

        double midX() {
            return (minX + maxX) / 2;
        }

        double midY() {
            return (minY + maxY) / 2;
        }

        int childFor(Point p) {
            return (p.x() >= midX() ? 1 : 0) | (p.y() >= midY() ? 2 : 0);
        }

        double distanceSquared(double x, double y) {
            double dx = Math.max(Math.max(minX - x, 0), x - maxX);
            double dy = Math.max(Math.max(minY - y, 0), y - maxY);
            return dx * dx + dy * dy;
        }

        boolean intersects(Rect r) {
            return r.minX() <= maxX && r.maxX() >= minX && r.minY() <= maxY && r.maxY() >= minY;
        }

        boolean covers(Point p) {
            return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY;
        }
    }

    private final Map<String, Point> table = new HashMap<>();
    private Node root;

    // ---------------------------------------------------------------- mutation

    public void insert(String id, double x, double y) {
        requireId(id);
        Point p = new Point(x, y);
        if (table.containsKey(id)) {
            throw new IllegalArgumentException("duplicate id: " + id);
        }
        add(new Entry(id, p));
    }

    public boolean remove(String id) {
        Point p = id == null ? null : table.get(id);
        if (p == null) {
            return false;
        }
        removeFrom(root, id, p);
        table.remove(id);
        return true;
    }

    public void move(String id, double x, double y) {
        requireId(id);
        Point np = new Point(x, y);
        if (!table.containsKey(id)) {
            throw new IllegalArgumentException("unknown id: " + id);
        }
        remove(id);
        add(new Entry(id, np));
    }

    private static void requireId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must be non-empty");
        }
    }

    private void add(Entry e) {
        Point p = e.point();
        if (root == null) {
            root = new Node(p.x() - 1, p.y() - 1, p.x() + 1, p.y() + 1, 0);
        } else if (!root.covers(p)) {
            regrow(p);
        }
        insertInto(root, e);
        table.put(e.id(), p);
    }

    /** Rebuild on a square twice as wide as old bounds united with the new point. */
    private void regrow(Point p) {
        double lx = Math.min(root.minX, p.x());
        double ly = Math.min(root.minY, p.y());
        double hx = Math.max(root.maxX, p.x());
        double hy = Math.max(root.maxY, p.y());
        double side = Math.max(hx - lx, hy - ly) * 2;
        double cx = (lx + hx) / 2;
        double cy = (ly + hy) / 2;
        List<Entry> all = new ArrayList<>();
        collect(root, all);
        root = new Node(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2, 0);
        for (Entry e : all) {
            insertInto(root, e);
        }
    }

    private static void insertInto(Node n, Entry e) {
        n.count++;
        if (n.leaf()) {
            n.items.add(e);
            if (n.items.size() > CAPACITY && n.depth < MAX_DEPTH) {
                split(n);
            }
            return;
        }
        insertInto(n.kids[n.childFor(e.point())], e);
    }

    private static void split(Node n) {
        double mx = n.midX();
        double my = n.midY();
        int d = n.depth + 1;
        n.kids = new Node[] {
            new Node(n.minX, n.minY, mx, my, d), new Node(mx, n.minY, n.maxX, my, d),
            new Node(n.minX, my, mx, n.maxY, d), new Node(mx, my, n.maxX, n.maxY, d)};
        List<Entry> old = n.items;
        n.items = null;
        for (Entry e : old) {
            insertInto(n.kids[n.childFor(e.point())], e);
        }
    }

    private static void removeFrom(Node n, String id, Point p) {
        n.count--;
        if (n.leaf()) {
            for (int i = 0; i < n.items.size(); i++) {
                if (n.items.get(i).id().equals(id)) {
                    n.items.remove(i);
                    return;
                }
            }
            throw new IllegalStateException("entry missing from leaf: " + id);
        }
        removeFrom(n.kids[n.childFor(p)], id, p);
        if (n.count <= CAPACITY) {
            List<Entry> all = new ArrayList<>();
            collect(n, all);
            n.kids = null;
            n.items = all;
        }
    }

    private static void collect(Node n, List<Entry> out) {
        if (n.leaf()) {
            out.addAll(n.items);
            return;
        }
        for (Node k : n.kids) {
            collect(k, out);
        }
    }

    // ----------------------------------------------------------------- queries

    public int size() {
        return table.size();
    }

    public Optional<Point> get(String id) {
        return Optional.ofNullable(id == null ? null : table.get(id));
    }

    public List<Entry> range(Rect r) {
        Objects.requireNonNull(r, "rect");
        List<Entry> out = new ArrayList<>();
        if (root != null) {
            rangeInto(root, r, out);
        }
        out.sort(Comparator.comparing(Entry::id));
        return out;
    }

    private static void rangeInto(Node n, Rect r, List<Entry> out) {
        if (n.count == 0 || !n.intersects(r)) {
            return;
        }
        if (n.leaf()) {
            for (Entry e : n.items) {
                if (r.contains(e.point())) {
                    out.add(e);
                }
            }
            return;
        }
        for (Node k : n.kids) {
            rangeInto(k, r, out);
        }
    }

    private static double dist2(Point p, double x, double y) {
        return (p.x() - x) * (p.x() - x) + (p.y() - y) * (p.y() - y);
    }

    public List<Entry> radius(double cx, double cy, double r) {
        if (!Double.isFinite(cx) || !Double.isFinite(cy) || !(r >= 0) || Double.isInfinite(r)) {
            throw new IllegalArgumentException("bad circle");
        }
        List<Entry> out = new ArrayList<>();
        if (root != null) {
            radiusInto(root, cx, cy, r * r, out);
        }
        out.sort(Comparator.comparingDouble((Entry e) -> dist2(e.point(), cx, cy)).thenComparing(Entry::id));
        return out;
    }

    private static void radiusInto(Node n, double cx, double cy, double r2, List<Entry> out) {
        if (n.count == 0 || n.distanceSquared(cx, cy) > r2) {
            return;
        }
        if (n.leaf()) {
            for (Entry e : n.items) {
                if (dist2(e.point(), cx, cy) <= r2) {
                    out.add(e);
                }
            }
            return;
        }
        for (Node k : n.kids) {
            radiusInto(k, cx, cy, r2, out);
        }
    }

    private record Item(double d2, Node node, Entry entry) {
    }

    public List<Entry> nearest(double x, double y, int k) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || k < 0) {
            throw new IllegalArgumentException("bad query");
        }
        List<Entry> out = new ArrayList<>();
        if (root == null || k == 0) {
            return out;
        }
        // Nodes sort before entries at equal distance so every tied entry is queued before one is emitted.
        Comparator<Item> order = Comparator.comparingDouble(Item::d2)
                .thenComparing((Item i) -> i.node() == null ? 1 : 0)
                .thenComparing((Item i) -> i.entry() == null ? "" : i.entry().id());
        PriorityQueue<Item> pq = new PriorityQueue<>(order);
        pq.add(new Item(root.distanceSquared(x, y), root, null));
        while (!pq.isEmpty() && out.size() < k) {
            Item it = pq.poll();
            if (it.node() == null) {
                out.add(it.entry());
            } else if (it.node().leaf()) {
                for (Entry e : it.node().items) {
                    pq.add(new Item(dist2(e.point(), x, y), null, e));
                }
            } else {
                for (Node kid : it.node().kids) {
                    if (kid.count > 0) {
                        pq.add(new Item(kid.distanceSquared(x, y), kid, null));
                    }
                }
            }
        }
        return out;
    }

    public List<Entry> inPolygon(List<Point> polygon) {
        Objects.requireNonNull(polygon, "polygon");
        if (polygon.size() < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 vertices");
        }
        double lx = Double.MAX_VALUE;
        double ly = Double.MAX_VALUE;
        double hx = -Double.MAX_VALUE;
        double hy = -Double.MAX_VALUE;
        for (Point v : polygon) {
            lx = Math.min(lx, v.x());
            ly = Math.min(ly, v.y());
            hx = Math.max(hx, v.x());
            hy = Math.max(hy, v.y());
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : range(new Rect(lx, ly, hx, hy))) {
            if (polygonContains(polygon, e.point())) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean polygonContains(List<Point> v, Point p) {
        int n = v.size();
        boolean inside = false;
        for (int i = 0; i < n; i++) {
            Point a = v.get(i);
            Point b = v.get((i + 1) % n);
            double cross = (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
            if (cross == 0 && p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                    && p.y() >= Math.min(a.y(), b.y()) && p.y() <= Math.max(a.y(), b.y())) {
                return true;
            }
            if ((a.y() > p.y()) != (b.y() > p.y())) {
                double xAt = a.x() + (p.y() - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (p.x() < xAt) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public Clusters cluster(double eps, int minPts) {
        if (!(eps >= 0) || Double.isInfinite(eps) || minPts < 1) {
            throw new IllegalArgumentException("bad DBSCAN parameters");
        }
        List<String> ids = new ArrayList<>(table.keySet());
        ids.sort(null);
        Map<String, List<String>> neighbours = new HashMap<>();
        Map<String, Integer> label = new HashMap<>();
        List<List<String>> clusters = new ArrayList<>();
        for (String id : ids) {
            if (label.containsKey(id) || neighboursOf(id, eps, neighbours).size() < minPts) {
                continue;
            }
            int c = clusters.size();
            List<String> members = new ArrayList<>();
            clusters.add(members);
            label.put(id, c);
            members.add(id);
            ArrayDeque<String> queue = new ArrayDeque<>(neighbours.get(id));
            while (!queue.isEmpty()) {
                String j = queue.poll();
                if (label.containsKey(j)) {
                    continue;
                }
                label.put(j, c);
                members.add(j);
                List<String> nb = neighboursOf(j, eps, neighbours);
                if (nb.size() >= minPts) {
                    queue.addAll(nb);
                }
            }
        }
        List<String> noise = new ArrayList<>();
        for (String id : ids) {
            if (!label.containsKey(id)) {
                noise.add(id);
            }
        }
        for (List<String> members : clusters) {
            members.sort(null);
        }
        return new Clusters(clusters, noise);
    }

    private List<String> neighboursOf(String id, double eps, Map<String, List<String>> cache) {
        List<String> cached = cache.get(id);
        if (cached == null) {
            Point p = table.get(id);
            cached = radius(p.x(), p.y(), eps).stream().map(Entry::id).toList();
            cache.put(id, cached);
        }
        return cached;
    }

    // ---------------------------------------------------------------- snapshot

    private List<Entry> sortedEntries() {
        List<Entry> all = new ArrayList<>();
        table.forEach((id, p) -> all.add(new Entry(id, p)));
        all.sort(Comparator.comparing(Entry::id));
        return all;
    }

    public byte[] snapshot() {
        List<Entry> all = sortedEntries();
        List<byte[]> idBytes = new ArrayList<>();
        int size = MAGIC.length + 4 + 4;
        for (Entry e : all) {
            byte[] b = e.id().getBytes(StandardCharsets.UTF_8);
            idBytes.add(b);
            size += 4 + b.length + 16;
        }
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.put(MAGIC).putInt(all.size());
        for (int i = 0; i < all.size(); i++) {
            byte[] b = idBytes.get(i);
            bb.putInt(b.length).put(b);
            bb.putLong(Double.doubleToRawLongBits(all.get(i).point().x()));
            bb.putLong(Double.doubleToRawLongBits(all.get(i).point().y()));
        }
        CRC32 crc = new CRC32();
        crc.update(bb.array(), 0, size - 4);
        bb.putInt((int) crc.getValue());
        return bb.array();
    }

    public static SpatialIndex restore(byte[] data) throws CorruptSnapshotException {
        if (data == null || data.length < MAGIC.length + 8) {
            throw new CorruptSnapshotException("snapshot too short");
        }
        try {
            return parse(data);
        } catch (BufferUnderflowException | IllegalArgumentException | IllegalStateException e) {
            throw new CorruptSnapshotException("malformed snapshot: " + e);
        }
    }

    private static SpatialIndex parse(byte[] data) throws CorruptSnapshotException {
        int bodyEnd = data.length - 4;
        CRC32 crc = new CRC32();
        crc.update(data, 0, bodyEnd);
        ByteBuffer bb = ByteBuffer.wrap(data);
        if (bb.getInt(bodyEnd) != (int) crc.getValue()) {
            throw new CorruptSnapshotException("checksum mismatch");
        }
        for (byte m : MAGIC) {
            if (bb.get() != m) {
                throw new CorruptSnapshotException("bad magic");
            }
        }
        int count = bb.getInt();
        if (count < 0 || (long) count * MIN_ENTRY_BYTES > bodyEnd - bb.position()) {
            throw new CorruptSnapshotException("bad count " + count);
        }
        SpatialIndex ix = new SpatialIndex();
        String previous = null;
        for (int i = 0; i < count; i++) {
            int len = bb.getInt();
            if (len < 1 || len > bodyEnd - bb.position() - 16) {
                throw new CorruptSnapshotException("bad id length " + len);
            }
            byte[] raw = new byte[len];
            bb.get(raw);
            String id;
            try {
                id = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException e) {
                throw new CorruptSnapshotException("id is not valid UTF-8");
            }
            double x = Double.longBitsToDouble(bb.getLong());
            double y = Double.longBitsToDouble(bb.getLong());
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new CorruptSnapshotException("non-finite coordinate");
            }
            if (previous != null && previous.compareTo(id) >= 0) {
                throw new CorruptSnapshotException("ids not strictly ascending at " + id);
            }
            previous = id;
            ix.insert(id, x, y);
        }
        if (bb.position() != bodyEnd) {
            throw new CorruptSnapshotException("trailing bytes");
        }
        return ix;
    }

    // -------------------------------------------------------------- invariants

    /** Maximum node depth; a single leaf has depth 0. */
    int depth() {
        return root == null ? 0 : depthOf(root);
    }

    private static int depthOf(Node n) {
        if (n.leaf()) {
            return 0;
        }
        int d = 0;
        for (Node k : n.kids) {
            d = Math.max(d, depthOf(k));
        }
        return d + 1;
    }

    /** Throws {@link AssertionError} when a structural invariant is violated. */
    void checkInvariants() {
        Set<String> seen = new HashSet<>();
        if (root != null) {
            check(root, seen);
        }
        if (!seen.equals(table.keySet())) {
            throw new AssertionError("tree ids differ from id table");
        }
        for (String id : seen) {
            Entry found = null;
            for (Entry e : range(new Rect(table.get(id).x(), table.get(id).y(), table.get(id).x(), table.get(id).y()))) {
                if (e.id().equals(id)) {
                    found = e;
                }
            }
            if (found == null) {
                throw new AssertionError("point query cannot find " + id);
            }
        }
    }

    private void check(Node n, Set<String> seen) {
        if (n.leaf()) {
            if (n.count != n.items.size()) {
                throw new AssertionError("leaf count mismatch");
            }
            if (n.items.size() > CAPACITY && n.depth < MAX_DEPTH) {
                throw new AssertionError("overfull leaf at depth " + n.depth);
            }
            for (Entry e : n.items) {
                if (!n.covers(e.point())) {
                    throw new AssertionError("point outside leaf bounds: " + e);
                }
                if (!e.point().equals(table.get(e.id())) || !seen.add(e.id())) {
                    throw new AssertionError("stale or duplicate entry " + e.id());
                }
            }
            return;
        }
        int sum = 0;
        for (Node k : n.kids) {
            sum += k.count;
            check(k, seen);
        }
        if (sum != n.count) {
            throw new AssertionError("internal count mismatch");
        }
        if (n.count <= CAPACITY) {
            throw new AssertionError("internal node should have collapsed");
        }
    }
}
