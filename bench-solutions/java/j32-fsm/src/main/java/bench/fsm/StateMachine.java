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

        State(String name, State parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(State source, String event, State target, BooleanSupplier guard) { }

    private final State topInitial;
    private final Map<State, State> remembered = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private State leaf;

    private StateMachine(State topInitial) {
        this.topInitial = topInitial;
        enterFresh(topInitial);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String current() {
        return leaf.name;
    }

    public List<String> active() {
        List<String> path = new ArrayList<>();
        for (State s = leaf; s != null; s = s.parent) path.add(0, s.name);
        return Collections.unmodifiableList(path);
    }

    public List<String> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public boolean fire(String event) {
        if (event == null) throw new IllegalArgumentException("event is null");
        for (State s = leaf; s != null; s = s.parent) {
            for (Trans t : s.transitions) {
                if (!t.event.equals(event)) continue;
                if (t.guard == null || t.guard.getAsBoolean()) {
                    take(t);
                    return true;
                }
            }
        }
        return false;
    }

    private void take(Trans t) {
        State s = t.source;
        State target = t.target;
        Set<State> sAnc = new HashSet<>();
        for (State x = s; x != null; x = x.parent) sAnc.add(x);
        State a = target;
        while (a != null && !sAnc.contains(a)) a = a.parent;
        if (a == s || a == target) a = (a == null) ? null : a.parent;

        State activeLeaf = leaf;
        for (State x = activeLeaf; x != null && x != a; x = x.parent) {
            if (x.history) remembered.put(x, activeLeaf);
            log.add("exit:" + x.name);
        }
        List<State> path = new ArrayList<>();
        for (State x = target; x != null && x != a; x = x.parent) path.add(0, x);
        for (State x : path) log.add("enter:" + x.name);
        descend(target);
    }

    private void enterFresh(State s) {
        log.add("enter:" + s.name);
        descend(s);
    }

    /** Called after s has been entered; continues into children until a leaf. */
    private void descend(State s) {
        State cur = s;
        while (!cur.children.isEmpty()) {
            State mem = cur.history ? remembered.get(cur) : null;
            if (mem != null) {
                List<State> path = new ArrayList<>();
                for (State x = mem; x != cur; x = x.parent) path.add(0, x);
                for (State x : path) log.add("enter:" + x.name);
                cur = mem;
                break;
            }
            cur = cur.initial;
            log.add("enter:" + cur.name);
        }
        leaf = cur;
    }

    /** Builder for state machines. */
    public static final class Builder {
        private final Map<String, State> states = new LinkedHashMap<>();
        private State topInitial;

        public Builder state(String name) {
            return add(name, null);
        }

        public Builder state(String name, String parent) {
            if (parent == null) throw new IllegalArgumentException("parent is null");
            return add(name, get(parent));
        }

        private Builder add(String name, State parent) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("bad name");
            if (states.containsKey(name)) throw new IllegalArgumentException("duplicate " + name);
            State s = new State(name, parent);
            if (parent != null) parent.children.add(s);
            states.put(name, s);
            return this;
        }

        private State get(String name) {
            State s = name == null ? null : states.get(name);
            if (s == null) throw new IllegalArgumentException("undeclared state " + name);
            return s;
        }

        public Builder initial(String state) {
            State s = get(state);
            if (s.parent == null) {
                if (topInitial != null) throw new IllegalArgumentException("second top initial");
                topInitial = s;
            } else {
                if (s.parent.initial != null) throw new IllegalArgumentException("second initial");
                s.parent.initial = s;
            }
            return this;
        }

        public Builder history(String composite) {
            get(composite).history = true;
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return transition(source, event, target, null, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            if (guard == null) throw new IllegalArgumentException("guard is null");
            return transition(source, event, target, guard, true);
        }

        private Builder transition(String source, String event, String target, BooleanSupplier guard, boolean g) {
            State s = get(source);
            State t = get(target);
            if (event == null) throw new IllegalArgumentException("event is null");
            s.transitions.add(new Trans(s, event, t, guard));
            return this;
        }

        public StateMachine build() {
            if (topInitial == null) throw new IllegalStateException("no top-level initial state");
            for (State s : states.values()) {
                if (!s.children.isEmpty() && s.initial == null) {
                    throw new IllegalStateException("composite without initial: " + s.name);
                }
                if (s.children.isEmpty() && s.history) {
                    throw new IllegalStateException("leaf with history: " + s.name);
                }
            }
            // Snapshot so later builder changes cannot affect the machine.
            Map<State, State> copy = new HashMap<>();
            for (State s : states.values()) {
                State c = new State(s.name, s.parent == null ? null : copy.get(s.parent));
                c.history = s.history;
                if (c.parent != null) c.parent.children.add(c);
                copy.put(s, c);
            }
            for (State s : states.values()) {
                State c = copy.get(s);
                if (s.initial != null) c.initial = copy.get(s.initial);
                for (Trans t : s.transitions) {
                    c.transitions.add(new Trans(c, t.event, copy.get(t.target), t.guard));
                }
            }
            return new StateMachine(copy.get(topInitial));
        }
    }
}
