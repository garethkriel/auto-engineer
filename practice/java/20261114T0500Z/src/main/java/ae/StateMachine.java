package ae;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Generic finite state machine over an enum type. */
public final class StateMachine<S extends Enum<S>> {
    /** One recorded move. */
    public record Transition<S extends Enum<S>>(S from, S to) { }

    private final Map<S, Set<S>> allowed;
    private final List<Transition<S>> history = new ArrayList<>();
    private S current;

    public StateMachine(Class<S> type, S initial, Map<S, Set<S>> transitions) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(transitions, "transitions");
        this.allowed = new EnumMap<>(type);
        for (Map.Entry<S, Set<S>> e : transitions.entrySet()) {
            Set<S> next = EnumSet.noneOf(type);
            next.addAll(e.getValue());
            allowed.put(e.getKey(), next);
        }
        this.current = initial;
    }

    public S current() {
        return current;
    }

    public boolean canMove(S target) {
        return target != null && allowed.getOrDefault(current, Set.of()).contains(target);
    }

    public void move(S target) {
        Objects.requireNonNull(target, "target");
        if (!canMove(target)) {
            throw new IllegalStateException("illegal transition " + current + " -> " + target);
        }
        history.add(new Transition<>(current, target));
        current = target;
    }

    public boolean isTerminal() {
        return allowed.getOrDefault(current, Set.of()).isEmpty();
    }

    public List<Transition<S>> history() {
        return Collections.unmodifiableList(history);
    }
}
