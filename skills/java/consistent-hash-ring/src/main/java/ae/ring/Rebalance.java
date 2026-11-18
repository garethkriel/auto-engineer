package ae.ring;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Computes which keys change owner between two rings. */
public final class Rebalance {
    private Rebalance() {}

    /** A key that moves from one node to another. */
    public record Move(String key, String from, String to) {}

    /**
     * Keys (in input order) whose owner differs. A key with no owner in one of the rings
     * is skipped, since there is no node to move it from or to.
     */
    public static List<Move> moves(HashRing oldRing, HashRing newRing, Iterable<String> keys) {
        Objects.requireNonNull(oldRing);
        Objects.requireNonNull(newRing);
        List<Move> out = new ArrayList<>();
        for (String k : keys) {
            var from = oldRing.owner(k);
            var to = newRing.owner(k);
            if (from.isPresent() && to.isPresent() && !from.get().equals(to.get())) {
                out.add(new Move(k, from.get(), to.get()));
            }
        }
        return out;
    }
}
