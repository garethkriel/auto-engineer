package bench.fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Hierarchical state machine with deep history. */
public final class StateMachine {

    private static final class Node {
        final String name;
        final Node parent;
        final List<Node> children = new ArrayList<>();
        Node initial;
        boolean history;
        final List<Trans> transitions = new ArrayList<>();

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(String event, Node target, BooleanSupplier guard) { }

    private final Node topInitial;
    private final Map<Node, Node> remembered = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private Node leaf;

    private StateMachine(Node topInitial) {
        this.topInitial = topInitial;
        enter(topInitial);
        descend(topInitial);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String current() {
        return leaf.name;
    }

    public List<String> active() {
        ArrayList<String> path = new ArrayList<>();
        for (Node n = leaf; n != null; n = n.parent) path.add(n.name);
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public boolean fire(String event) {
        if (event == null) throw new IllegalArgumentException("event");
        for (Node s = leaf; s != null; s = s.parent) {
            for (Trans t : s.transitions) {
                if (!t.event.equals(event)) continue;
                if (t.guard == null || t.guard.getAsBoolean()) {
                    take(s, t.target);
                    return true;
                }
            }
        }
        return false;
    }

    private void take(Node s, Node t) {
        Node a = commonAncestor(s, t);
        if (a == s || a == t) a = a.parent;
        Node oldLeaf = leaf;
        for (Node n = oldLeaf; n != null && n != a; n = n.parent) {
            if (n.history) remembered.put(n, oldLeaf);
            log.add("exit:" + n.name);
        }
        List<Node> path = new ArrayList<>();
        for (Node n = t; n != null && n != a; n = n.parent) path.add(n);
        Collections.reverse(path);
        for (Node n : path) enter(n);
        descend(t);
    }

    private static Node commonAncestor(Node s, Node t) {
        for (Node x = t; x != null; x = x.parent) {
            for (Node y = s; y != null; y = y.parent) {
                if (x == y) return x;
            }
        }
        return null;
    }

    private void enter(Node n) {
        log.add("enter:" + n.name);
        leaf = n;
    }

    /** Continue entering below an already-entered node until a leaf is reached. */
    private void descend(Node n) {
        while (!n.children.isEmpty()) {
            Node mem = n.history ? remembered.get(n) : null;
            if (mem != null) {
                List<Node> path = new ArrayList<>();
                for (Node x = mem; x != n; x = x.parent) path.add(x);
                Collections.reverse(path);
                for (Node x : path) enter(x);
                return;
            }
            n = n.initial;
            enter(n);
        }
    }

    /** Builder for state machines. */
    public static final class Builder {
        private final Map<String, Node> states = new LinkedHashMap<>();
        private boolean topInitialSet;
        private Node topInitial;
        private final List<Node> historyNodes = new ArrayList<>();

        private void checkName(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name");
            if (states.containsKey(name)) throw new IllegalArgumentException("duplicate " + name);
        }

        private Node need(String name) {
            Node n = name == null ? null : states.get(name);
            if (n == null) throw new IllegalArgumentException("undeclared " + name);
            return n;
        }

        public Builder state(String name) {
            checkName(name);
            states.put(name, new Node(name, null));
            return this;
        }

        public Builder state(String name, String parent) {
            checkName(name);
            Node p = need(parent);
            Node n = new Node(name, p);
            p.children.add(n);
            states.put(name, n);
            return this;
        }

        public Builder initial(String state) {
            Node n = need(state);
            if (n.parent == null) {
                if (topInitialSet) throw new IllegalArgumentException("second top-level initial");
                topInitialSet = true;
                topInitial = n;
            } else {
                if (n.parent.initial != null) throw new IllegalArgumentException("second initial");
                n.parent.initial = n;
            }
            return this;
        }

        public Builder history(String composite) {
            Node n = need(composite);
            if (!historyNodes.contains(n)) historyNodes.add(n);
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return transition(source, event, target, null, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            if (guard == null) throw new IllegalArgumentException("guard");
            return transition(source, event, target, guard, true);
        }

        private Builder transition(String source, String event, String target, BooleanSupplier guard, boolean g) {
            Node s = need(source);
            Node t = need(target);
            if (event == null) throw new IllegalArgumentException("event");
            s.transitions.add(new Trans(event, t, guard));
            return this;
        }

        public StateMachine build() {
            if (!topInitialSet) throw new IllegalStateException("no initial state");
            for (Node n : states.values()) {
                if (!n.children.isEmpty() && n.initial == null) {
                    throw new IllegalStateException("composite without initial: " + n.name);
                }
            }
            for (Node n : historyNodes) {
                if (n.children.isEmpty()) throw new IllegalStateException("history on leaf: " + n.name);
            }
            // snapshot so later builder changes do not affect the machine
            Map<Node, Node> copy = new HashMap<>();
            for (Node n : states.values()) {
                Node c = new Node(n.name, n.parent == null ? null : copy.get(n.parent));
                c.history = historyNodes.contains(n);
                copy.put(n, c);
                if (c.parent != null) c.parent.children.add(c);
            }
            for (Node n : states.values()) {
                Node c = copy.get(n);
                if (n.initial != null) c.initial = copy.get(n.initial);
                for (Trans t : n.transitions) {
                    c.transitions.add(new Trans(t.event, copy.get(t.target), t.guard));
                }
            }
            return new StateMachine(copy.get(topInitial));
        }
    }
}
