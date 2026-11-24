package ae.chord;

import java.util.List;

/** Result of a routed lookup: the node owning the key, the hop count and the nodes visited. */
public record Lookup(long owner, int hops, List<Long> path) {
    public Lookup {
        path = List.copyOf(path);
    }
}
