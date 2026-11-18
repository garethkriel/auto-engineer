package ae.ring;

import java.util.*;

/**
 * Immutable consistent-hash ring with weighted virtual nodes.
 *
 * <p>Positions are 64-bit hashes XOR-ed with {@link Long#MIN_VALUE}, so ordinary signed
 * comparison orders them as unsigned values. Point {@code i} owns the arc
 * {@code (point[i-1], point[i]]}.
 */
public final class HashRing {
    public static final int DEFAULT_VNODES = 128;

    private final Hasher hasher;
    private final int vnodesPerWeight;
    private final SortedMap<String, Integer> nodes; // id -> weight
    private final long[] points;                    // sorted, distinct
    private final String[] pointOwner;              // parallel to points

    private HashRing(Hasher hasher, int vnodesPerWeight, SortedMap<String, Integer> nodes) {
        this.hasher = hasher;
        this.vnodesPerWeight = vnodesPerWeight;
        this.nodes = Collections.unmodifiableSortedMap(nodes);
        // Resolve positions; a collision is won by the smaller id (order independent).
        TreeMap<Long, String> pos = new TreeMap<>();
        for (Map.Entry<String, Integer> e : nodes.entrySet()) {
            int count = Math.multiplyExact(e.getValue(), vnodesPerWeight);
            for (int i = 0; i < count; i++) {
                long p = hasher.hash(e.getKey() + "#" + i) ^ Long.MIN_VALUE;
                pos.merge(p, e.getKey(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        this.points = new long[pos.size()];
        this.pointOwner = new String[pos.size()];
        int i = 0;
        for (Map.Entry<Long, String> e : pos.entrySet()) {
            points[i] = e.getKey();
            pointOwner[i++] = e.getValue();
        }
    }

    public static HashRing empty() {
        return empty(Hasher.DEFAULT, DEFAULT_VNODES);
    }

    public static HashRing empty(Hasher hasher, int vnodesPerWeight) {
        Objects.requireNonNull(hasher, "hasher");
        if (vnodesPerWeight < 1) throw new IllegalArgumentException("vnodesPerWeight must be >= 1");
        return new HashRing(hasher, vnodesPerWeight, new TreeMap<>());
    }

    /** Returns a ring with {@code id} added, or its weight replaced. */
    public HashRing withNode(String id, int weight) {
        Objects.requireNonNull(id, "id");
        if (id.isEmpty()) throw new IllegalArgumentException("empty node id");
        if (weight < 1) throw new IllegalArgumentException("weight must be >= 1");
        TreeMap<String, Integer> m = new TreeMap<>(nodes);
        m.put(id, weight);
        return new HashRing(hasher, vnodesPerWeight, m);
    }

    /** Returns a ring without {@code id}; this ring itself if it is not a member. */
    public HashRing withoutNode(String id) {
        Objects.requireNonNull(id, "id");
        if (!nodes.containsKey(id)) return this;
        TreeMap<String, Integer> m = new TreeMap<>(nodes);
        m.remove(id);
        return new HashRing(hasher, vnodesPerWeight, m);
    }

    /** Member ids with weights, sorted by id. */
    public SortedMap<String, Integer> nodes() {
        return nodes;
    }

    /** Index of the first point at or after the key position, wrapping to 0. Ring must be non-empty. */
    private int start(String key) {
        long p = hasher.hash(Objects.requireNonNull(key, "key")) ^ Long.MIN_VALUE;
        int idx = Arrays.binarySearch(points, p);
        if (idx < 0) idx = -idx - 1;
        return idx == points.length ? 0 : idx;
    }

    public Optional<String> owner(String key) {
        Objects.requireNonNull(key, "key");
        if (points.length == 0) return Optional.empty();
        return Optional.of(pointOwner[start(key)]);
    }

    /** First {@code n} distinct nodes clockwise from the key. */
    public List<String> preferenceList(String key, int n) {
        Objects.requireNonNull(key, "key");
        if (n < 0) throw new IllegalArgumentException("n < 0");
        if (points.length == 0 || n == 0) return List.of();
        int want = Math.min(n, nodes.size());
        List<String> out = new ArrayList<>(want);
        Set<String> seen = new HashSet<>();
        int i = start(key);
        // Every node that still has a point appears within one lap. A node whose points all
        // lost collisions is not on the ring and cannot be listed.
        for (int step = 0; step < points.length && out.size() < want; step++) {
            String o = pointOwner[(i + step) % points.length];
            if (seen.add(o)) out.add(o);
        }
        return Collections.unmodifiableList(out);
    }

    /** Fraction of the hash space owned by each node that has at least one point. */
    public Map<String, Double> share() {
        Map<String, Double> out = new TreeMap<>();
        int k = points.length;
        if (k == 0) return out;
        if (k == 1) {
            out.put(pointOwner[0], 1.0);
            return out;
        }
        final double total = 18446744073709551616.0; // 2^64
        for (int i = 0; i < k; i++) {
            long prev = points[(i + k - 1) % k];
            long len = points[i] - prev; // wraps mod 2^64, correct unsigned arc length
            double d = (len >>> 1) * 2.0 + (len & 1);
            out.merge(pointOwner[i], d / total, Double::sum);
        }
        return out;
    }

    /**
     * Bounded-load assignment: every node takes at most {@code ceil(c * keys / nodes)} keys; a
     * key goes to the first node in its preference order that still has room.
     */
    public Map<String, String> assignBounded(List<String> keys, double c) {
        Objects.requireNonNull(keys, "keys");
        if (!(c >= 1.0)) throw new IllegalArgumentException("c must be >= 1");
        Map<String, String> out = new LinkedHashMap<>();
        if (keys.isEmpty()) return out;
        if (points.length == 0) throw new IllegalStateException("empty ring");
        Set<String> onRing = new HashSet<>(Arrays.asList(pointOwner));
        int n = onRing.size();
        long cap = (long) Math.ceil(c * keys.size() / n);
        Map<String, Integer> load = new HashMap<>();
        for (String k : keys) {
            if (out.containsKey(k)) throw new IllegalArgumentException("duplicate key " + k);
            String chosen = null;
            for (String cand : preferenceList(k, n)) {
                if (load.getOrDefault(cand, 0) < cap) {
                    chosen = cand;
                    break;
                }
            }
            // cap * n >= keys.size(), so some node always has room.
            load.merge(Objects.requireNonNull(chosen), 1, Integer::sum);
            out.put(k, chosen);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HashRing r && vnodesPerWeight == r.vnodesPerWeight
                && nodes.equals(r.nodes) && Arrays.equals(points, r.points)
                && Arrays.equals(pointOwner, r.pointOwner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vnodesPerWeight, nodes, Arrays.hashCode(points), Arrays.hashCode(pointOwner));
    }

    @Override
    public String toString() {
        return "HashRing" + nodes + " points=" + points.length;
    }
}
