package ae.mc;

import java.util.Objects;

/** One labelled transition to {@code target}. */
public record Move<S>(String label, S target) {
    public Move {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(target, "target");
    }
}
