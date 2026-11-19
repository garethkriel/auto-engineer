package ae.swim;

import java.util.Objects;

/** A gossiped claim about one node. */
public record Update(String node, Status status, int incarnation) {
    public Update {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(status, "status");
        if (incarnation < 0) {
            throw new IllegalArgumentException("incarnation must be >= 0: " + incarnation);
        }
    }

    /**
     * Whether this claim supersedes {@code old} (about the same node). DEAD is absorbing; ALIVE needs a strictly
     * higher incarnation; SUSPECT wins ties against ALIVE but not against SUSPECT.
     */
    public boolean overrides(Update old) {
        if (!node.equals(old.node)) {
            throw new IllegalArgumentException("different nodes: " + node + " vs " + old.node);
        }
        if (old.status == Status.DEAD) {
            return false;
        }
        if (status == Status.DEAD) {
            return true;
        }
        if (status == Status.ALIVE) {
            return incarnation > old.incarnation;
        }
        if (old.status == Status.ALIVE) {
            return incarnation >= old.incarnation;
        }
        return incarnation > old.incarnation;
    }
}
