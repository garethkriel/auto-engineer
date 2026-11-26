package ae.lr;

import java.util.List;

/** A table cell holding more than one action. */
public record Conflict(int state, String symbol, Kind kind, List<Action> actions) {

    /** Kind of conflict. */
    public enum Kind { SHIFT_REDUCE, REDUCE_REDUCE }

    /** Compact constructor: defensive copy. */
    public Conflict {
        actions = List.copyOf(actions);
    }
}
