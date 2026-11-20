package bench.fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Hierarchical state machine with deep history. */
public final class StateMachine {
    private static final class State {
        final String name;
        final State parent;
        final List<State> children = new ArrayList<>();
        final List<Trans> transitions = new ArrayList<>();
        State initial;
        boolean history;
        State remembered;

        State(String name, State parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(String event, State target, BooleanSupplier guard) { }

    private final List<String> log = new ArrayList<>();
    private State leaf;

    private StateMachine(State topInitial) {

        enterFrom(null, topInitial);
    }

    /** Creates a builder. */
    public static Builder builder() {
        return new Builder();
    }

    public String current() {
        return leaf.name;
    }

    public List<String> active() {
        List<String> path = new ArrayList<>();
        for (State s = leaf; s != null; s = s.parent) {
            path.add(s.name);
        }
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public boolean fire(String event) {
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        for (State s = leaf; s != null; s = s.parent) {
            for (Trans t : s.transitions) {
                if (t.event.equals(event) && (t.guard == null || t.guard.getAsBoolean())) {
                    take(s, t.target);
                    return true;
                }
            }
        }
        return false;
    }

    private void take(State source, State target) {
        Set<State> srcAnc = new HashSet<>();
        for (State s = source; s != null; s = s.parent) {
            srcAnc.add(s);
        }
        State a = target;
        while (a != null && !srcAnc.contains(a)) {
            a = a.parent;
        }
        if (a == source || a == target) {
            a = a == null ? null : a.parent;
        }
        State activeLeaf = leaf;
        for (State s = leaf; s != a; s = s.parent) {
            log.add("exit:" + s.name);
            if (s.history) {
                s.remembered = activeLeaf;
            }
        }
        enterFrom(a, target);
    }

    /** Enters states strictly below {@code above} down to {@code target}, then descends. */
    private void enterFrom(State above, State target) {
        List<State> path = new ArrayList<>();
        for (State s = target; s != above; s = s.parent) {
            path.add(s);
        }
        Collections.reverse(path);
        for (State s : path) {
            log.add("enter:" + s.name);
            leaf = s;
        }
        descend(target);
    }

    private void descend(State s) {
        State cur = s;
        while (!cur.children.isEmpty()) {
            if (cur.history && cur.remembered != null) {
                State rem = cur.remembered;
                List<State> path = new ArrayList<>();
                for (State x = rem; x != cur; x = x.parent) {
                    path.add(x);
                }
                Collections.reverse(path);
                for (State x : path) {
                    log.add("enter:" + x.name);
                }
                leaf = rem;
                return;
            }
            cur = cur.initial;
            log.add("enter:" + cur.name);
            leaf = cur;
        }
        leaf = cur;
    }

    /** Builder for state machines. */
    public static final class Builder {
        private final Map<String, State> states = new LinkedHashMap<>();
        private State topInitial;

        private Builder() { }

        private State require(String name) {
            State s = name == null ? null : states.get(name);
            if (s == null) {
                throw new IllegalArgumentException("undeclared state: " + name);
            }
            return s;
        }

        public Builder state(String name) {
            return add(name, null);
        }

        public Builder state(String name, String parent) {
            return add(name, require(parent));
        }

        private Builder add(String name, State parent) {
            if (name == null || name.isEmpty() || states.containsKey(name)) {
                throw new IllegalArgumentException("bad or duplicate name: " + name);
            }
            State s = new State(name, parent);
            states.put(name, s);
            if (parent != null) {
                parent.children.add(s);
            }
            return this;
        }

        public Builder initial(String state) {
            State s = require(state);
            if (s.parent == null) {
                if (topInitial != null) {
                    throw new IllegalArgumentException("second top-level initial");
                }
                topInitial = s;
            } else {
                if (s.parent.initial != null) {
                    throw new IllegalArgumentException("second initial for " + s.parent.name);
                }
                s.parent.initial = s;
            }
            return this;
        }

        public Builder history(String composite) {
            require(composite).history = true;
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return transition(source, event, target, null, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            return transition(source, event, target, guard, true);
        }

        private Builder transition(String source, String event, String target, BooleanSupplier guard,
                                   boolean guardGiven) {
            State s = require(source);
            State t = require(target);
            if (event == null || (guardGiven && guard == null)) {
                throw new IllegalArgumentException("null event or guard");
            }
            s.transitions.add(new Trans(event, t, guard));
            return this;
        }

        public StateMachine build() {
            if (topInitial == null) {
                throw new IllegalStateException("no top-level initial state");
            }
            for (State s : states.values()) {
                if (!s.children.isEmpty() && s.initial == null) {
                    throw new IllegalStateException("composite without initial: " + s.name);
                }
                if (s.children.isEmpty() && s.history) {
                    throw new IllegalStateException("history on leaf: " + s.name);
                }
            }
            return new StateMachine(topInitial);
        }
    }
}
