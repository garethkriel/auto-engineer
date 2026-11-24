package ae.chord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Independent model of a settled ring: sorted live ids answer every placement question directly. */
public final class RingOracle {
    private final Ring ring;
    private final TreeSet<Long> ids;

    public RingOracle(Ring ring, Collection<Long> liveIds) {
        if (liveIds.isEmpty()) {
            throw new IllegalArgumentException("no live nodes");
        }
        this.ring = ring;
        this.ids = new TreeSet<>(liveIds);
    }

    /** First live id at or clockwise after {@code key}. */
    public long successor(long key) {
        Long c = ids.ceiling(ring.mod(key));
        return c != null ? c : ids.first();
    }

    /** Last live id strictly before {@code id} going counter-clockwise. */
    public long predecessor(long id) {
        Long f = ids.lower(id);
        return f != null ? f : ids.last();
    }

    /** The {@code count} live ids following {@code id} clockwise, excluding it (fewer if the ring is small). */
    public List<Long> following(long id, int count) {
        List<Long> out = new ArrayList<>();
        long cur = id;
        while (out.size() < count && out.size() < ids.size() - 1) {
            Long n = ids.higher(cur);
            cur = n != null ? n : ids.first();
            out.add(cur);
        }
        return out;
    }

    /** Owner of {@code key} plus the next nodes clockwise: the nodes that must hold a copy. */
    public List<Long> replicas(long key, int r) {
        long owner = successor(key);
        List<Long> out = new ArrayList<>();
        out.add(owner);
        out.addAll(following(owner, r - 1));
        return out;
    }
}
