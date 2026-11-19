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
        Node remembered;
        final List<Trans> trans = new ArrayList<>();

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(String event, Node target, BooleanSupplier guard) { }

    private final List<String> log = new ArrayList<>();
    private Node leaf;

    private StateMachine(Node topInitial) {
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

    private void enter(Node n) {
        log.add("enter:" + n.name);
        leaf = n;
    }

    /** After n has been entered: continue to a leaf via history or initial children. */
    private void descend(Node n) {
        if (n.children.isEmpty()) return;
        if (n.history && n.remembered != null) {
            List<Node> path = new ArrayList<>();
            for (Node x = n.remembered; x != n; x = x.parent) path.add(x);
            Collections.reverse(path);
            for (Node x : path) enter(x);
            return;
        }
        Node c = n.initial;
        enter(c);
        descend(c);
    }

    public boolean fire(String event) {
        if (event == null) throw new IllegalArgumentException("event is null");
        for (Node s = leaf; s != null; s = s.parent) {
            for (Trans t : s.trans) {
                if (!t.event.equals(event)) continue;
                if (t.guard == null || t.guard.getAsBoolean()) {
                    take(s, t.target);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAncestorOrSelf(Node a, Node n) {
        for (Node x = n; x != null; x = x.parent) if (x == a) return true;
        return false;
    }

    private void take(Node s, Node t) {
        Node a = s;
        while (a != null && !isAncestorOrSelf(a, t)) a = a.parent;
        if (a == s || a == t) a = a.parent;
        Node activeLeaf = leaf;
        for (Node x = activeLeaf; x != a; x = x.parent) {
            if (x.history) x.remembered = activeLeaf;
            log.add("exit:" + x.name);
        }
        List<Node> path = new ArrayList<>();
        for (Node x = t; x != a; x = x.parent) path.add(x);
        Collections.reverse(path);
        for (Node x : path) enter(x);
        descend(t);
    }

    public static final class Builder {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private Node topInitial;

        private Node declared(String name) {
            if (name == null) throw new IllegalArgumentException("name is null");
            Node n = nodes.get(name);
            if (n == null) throw new IllegalArgumentException("undeclared state: " + name);
            return n;
        }

        private Builder add(String name, Node parent) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("bad state name");
            if (nodes.containsKey(name)) throw new IllegalArgumentException("duplicate state: " + name);
            Node n = new Node(name, parent);
            nodes.put(name, n);
            if (parent != null) parent.children.add(n);
            return this;
        }

        public Builder state(String name) {
            return add(name, null);
        }

        public Builder state(String name, String parent) {
            Node p = declared(parent);
            return add(name, p);
        }

        public Builder initial(String state) {
            Node n = declared(state);
            if (n.parent == null) {
                if (topInitial != null) throw new IllegalArgumentException("second top-level initial");
                topInitial = n;
            } else {
                if (n.parent.initial != null) throw new IllegalArgumentException("second initial for " + n.parent.name);
                n.parent.initial = n;
            }
            return this;
        }

        public Builder history(String composite) {
            declared(composite).history = true;
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return add(source, event, target, null, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            return add(source, event, target, guard, true);
        }

        private Builder add(String source, String event, String target, BooleanSupplier guard, boolean needGuard) {
            Node s = declared(source);
            Node t = declared(target);
            if (event == null) throw new IllegalArgumentException("event is null");
            if (needGuard && guard == null) throw new IllegalArgumentException("guard is null");
            s.trans.add(new Trans(event, t, guard));
            return this;
        }

        public StateMachine build() {
            if (topInitial == null) throw new IllegalStateException("no top-level initial state");
            for (Node n : nodes.values()) {
                if (!n.children.isEmpty() && n.initial == null) {
                    throw new IllegalStateException("composite without initial child: " + n.name);
                }
                if (n.children.isEmpty() && n.history) {
                    throw new IllegalStateException("history on a leaf: " + n.name);
                }
            }
            // Copy so the machine is independent of later builder changes.
            Map<Node, Node> map = new HashMap<>();
            for (Node n : nodes.values()) {
                Node c = new Node(n.name, n.parent == null ? null : map.get(n.parent));
                c.history = n.history;
                map.put(n, c);
                if (c.parent != null) c.parent.children.add(c);
            }
            for (Node n : nodes.values()) {
                Node c = map.get(n);
                if (n.initial != null) c.initial = map.get(n.initial);
                for (Trans t : n.trans) c.trans.add(new Trans(t.event, map.get(t.target), t.guard));
            }
            return new StateMachine(map.get(topInitial));
        }
    }
}
