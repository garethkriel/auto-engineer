package ae;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Order lifecycle states. */
public enum OrderState {
    NEW, PAID, SHIPPED, DELIVERED, CANCELLED;

    /** A fresh machine in {@link #NEW} with the standard lifecycle. */
    public static StateMachine<OrderState> newMachine() {
        return new StateMachine<>(OrderState.class, NEW, Map.of(
                NEW, Set.of(PAID, CANCELLED),
                PAID, Set.of(SHIPPED, CANCELLED),
                SHIPPED, Set.of(DELIVERED)));
    }

    public static Optional<OrderState> parse(String text) {
        if (text == null) return Optional.empty();
        String t = text.trim().toUpperCase(Locale.ROOT);
        for (OrderState s : values()) {
            if (s.name().equals(t)) return Optional.of(s);
        }
        return Optional.empty();
    }
}
