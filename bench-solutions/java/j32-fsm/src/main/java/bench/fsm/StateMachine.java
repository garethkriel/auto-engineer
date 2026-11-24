package bench.fsm;

import java.util.ArrayList;
import java.util.Collections;
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
        final List<Trans> transitions = new ArrayList<>();
        Node initialChild;
        boolean history;
        Node remembered;

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(String event, Node target, BooleanSupplier guard) { }

    private final Node topInitial;
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
        List<String> path = new ArrayList<>();
        for (Node n = leaf; n != null; n = n.parent) path.add(n.name);
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    public List<String> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public boolean fire(String event) {
        if (event == null) throw new IllegalArgumentException("event is null");
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
        List<Node> sChain = chain(s);
        List<Node> tChain = chain(t);
        Node a = null;
        for (Node n : sChain) {
            if (tChain.contains(n)) {
                a = n;
                break;
            }
        }
        if (a == s || a == t) a = a.parent;
        // exit
        Node cur = leaf;
        Node exitLeaf = leaf;
        while (cur != null && cur != a) {
            log.add("exit:" + cur.name);
            if (cur.history) cur.remembered = exitLeaf;
            cur = cur.parent;
        }
        // enter from below a down to t
        List<Node> path = new ArrayList<>();
        for (Node n = t; n != null && n != a; n = n.parent) path.add(n);
        Collections.reverse(path);
        for (Node n : path) enter(n);
        leaf = t;
        descend(t);
    }

    /** Self-and-ancestors, innermost first. */
    private static List<Node> chain(Node n) {
        List<Node> c = new ArrayList<>();
        for (; n != null; n = n.parent) c.add(n);
        return c;
    }

    private void enter(Node n) {
        log.add("enter:" + n.name);
        leaf = n;
    }

    /** Continues entering below n (already entered) until a leaf is reached. */
    private void descend(Node n) {
        while (!n.children.isEmpty()) {
            if (n.history && n.remembered != null) {
                List<Node> path = new ArrayList<>();
                for (Node x = n.remembered; x != n; x = x.parent) path.add(x);
                Collections.reverse(path);
                for (Node x : path) enter(x);
                return;
            }
            n = n.initialChild;
            enter(n);
        }
        leaf = n;
    }

    public static final class Builder {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Node> top = new ArrayList<>();
        private Node topInitial;

        private Node require(String name, String what) {
            Node n = name == null ? null : nodes.get(name);
            if (n == null) throw new IllegalArgumentException("undeclared " + what + ": " + name);
            return n;
        }

        public Builder state(String name) {
            return declare(name, null);
        }

        public Builder state(String name, String parent) {
            Node p = require(parent, "parent");
            return declare(name, p);
        }

        private Builder declare(String name, Node parent) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("bad state name");
            if (nodes.containsKey(name)) throw new IllegalArgumentException("duplicate state: " + name);
            Node n = new Node(name, parent);
            nodes.put(name, n);
            if (parent == null) top.add(n);
            else parent.children.add(n);
            return this;
        }

        public Builder initial(String state) {
            Node n = require(state, "initial state");
            if (n.parent == null) {
                if (topInitial != null) throw new IllegalArgumentException("second top-level initial");
                topInitial = n;
            } else {
                if (n.parent.initialChild != null) throw new IllegalArgumentException("second initial for " + n.parent.name);
                n.parent.initialChild = n;
            }
            return this;
        }

        public Builder history(String composite) {
            require(composite, "history state").history = true;
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return add(source, event, target, null, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            return add(source, event, target, guard, true);
        }

        private Builder add(String source, String event, String target, BooleanSupplier guard, boolean guarded) {
            Node s = require(source, "source");
            Node t = require(target, "target");
            if (event == null) throw new IllegalArgumentException("event is null");
            if (guarded && guard == null) throw new IllegalArgumentException("guard is null");
            s.transitions.add(new Trans(event, t, guard));
            return this;
        }

        public StateMachine build() {
            if (topInitial == null) throw new IllegalStateException("no top-level initial state");
            for (Node n : nodes.values()) {
                if (!n.children.isEmpty() && n.initialChild == null)
                    throw new IllegalStateException("composite without initial child: " + n.name);
                if (n.children.isEmpty() && n.history)
                    throw new IllegalStateException("leaf with history: " + n.name);
            }
            // copy so each machine has its own mutable history
            Map<String, Node> copy = new LinkedHashMap<>();
            for (Node n : nodes.values()) {
                Node c = new Node(n.name, n.parent == null ? null : copy.get(n.parent.name));
                c.history = n.history;
                if (c.parent != null) c.parent.children.add(c);
                copy.put(n.name, c);
            }
            for (Node n : nodes.values()) {
                Node c = copy.get(n.name);
                if (n.initialChild != null) c.initialChild = copy.get(n.initialChild.name);
                for (Trans t : n.transitions) c.transitions.add(new Trans(t.event, copy.get(t.target.name), t.guard));
            }
            return new StateMachine(copy.get(topInitial.name));
        }
    }
}
