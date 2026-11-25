package ae.kad;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Flat 64-bucket Kademlia routing table with LRU buckets and per-bucket replacement caches. */
public final class RoutingTable {
    private final NodeId self;
    private final int k;
    private final List<List<NodeId>> buckets = new ArrayList<>();
    private final List<List<NodeId>> replacements = new ArrayList<>();

    public RoutingTable(NodeId self, int k) {
        this.self = Objects.requireNonNull(self);
        if (k < 1) throw new IllegalArgumentException("k must be >= 1");
        this.k = k;
        for (int i = 0; i < 64; i++) {
            buckets.add(new ArrayList<>());
            replacements.add(new ArrayList<>());
        }
    }

    /**
     * Records that {@code id} was heard from. Returns the least recently seen contact of the bucket
     * when {@code id} is new and the bucket is full (the caller should ping it), otherwise empty.
     */
    public synchronized Optional<NodeId> touch(NodeId id) {
        int b = self.bucketIndex(id);
        if (b < 0) return Optional.empty();
        List<NodeId> bucket = buckets.get(b);
        if (bucket.remove(id)) {
            bucket.add(id);
            return Optional.empty();
        }
        if (bucket.size() < k) {
            replacements.get(b).remove(id);
            bucket.add(id);
            return Optional.empty();
        }
        List<NodeId> cache = replacements.get(b);
        cache.remove(id);
        cache.add(id);
        if (cache.size() > k) cache.remove(0);
        return Optional.of(bucket.get(0));
    }

    /** Removes a contact; the newest replacement (if any) takes its place at the tail. */
    public synchronized void evict(NodeId id) {
        int b = self.bucketIndex(id);
        if (b < 0) return;
        List<NodeId> cache = replacements.get(b);
        if (buckets.get(b).remove(id)) {
            if (!cache.isEmpty()) buckets.get(b).add(cache.remove(cache.size() - 1));
        } else {
            cache.remove(id);
        }
    }

    public synchronized boolean contains(NodeId id) {
        int b = self.bucketIndex(id);
        return b >= 0 && buckets.get(b).contains(id);
    }

    public synchronized int size() {
        int n = 0;
        for (List<NodeId> b : buckets) n += b.size();
        return n;
    }

    /** Up to {@code n} known contacts, nearest to {@code target} first. */
    public synchronized List<NodeId> closest(NodeId target, int n) {
        List<NodeId> all = new ArrayList<>();
        for (List<NodeId> b : buckets) all.addAll(b);
        all.sort(NodeId.closerTo(target));
        return new ArrayList<>(all.subList(0, Math.min(Math.max(n, 0), all.size())));
    }

    /** Bucket contents, least recently seen first. */
    public synchronized List<NodeId> bucket(int index) {
        return List.copyOf(buckets.get(index));
    }

    /** Replacement cache of a bucket, oldest first. */
    public synchronized List<NodeId> replacements(int index) {
        return List.copyOf(replacements.get(index));
    }
}
