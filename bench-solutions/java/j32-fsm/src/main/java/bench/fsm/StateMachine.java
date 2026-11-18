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
        final List<Trans> transitions = new ArrayList<>();
        Node initial;
        boolean history;
        Node remembered;

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private record Trans(String event, Node target, BooleanSupplier guard) { }

    private final List<String> log = new ArrayList<>();
    private Node leaf;

    private StateMachine(Builder b) {
        Map<String, Node> nodes = new HashMap<>();
        // declaration order guarantees parents are created before children
        for (Builder.Def d : b.defs.values()) {
            Node n = new Node(d.name, d.parent == null ? null : nodes.get(d.parent));
            nodes.put(d.name, n);
            if (n.parent != null) {
                n.parent.children.add(n);
            }
        }
        for (Builder.Def d : b.defs.values()) {
            Node n = nodes.get(d.name);
            n.history = d.history;
            if (d.initial != null) {
                n.initial = nodes.get(d.initial);
            }
        }
        for (Builder.TDef t : b.transitions) {
            nodes.get(t.source).transitions.add(new Trans(t.event, nodes.get(t.target), t.guard));
        }
        enterSubtree(nodes.get(b.topInitial));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String current() {
        return leaf.name;
    }

    public List<String> active() {
        List<String> path = new ArrayList<>();
        for (Node n = leaf; n != null; n = n.parent) {
            path.add(n.name);
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
        for (Node s = leaf; s != null; s = s.parent) {
            for (Trans t : s.transitions) {
                if (!t.event.equals(event)) {
                    continue;
                }
                if (t.guard == null || t.guard.getAsBoolean()) {
                    take(s, t.target);
                    return true;
                }
            }
        }
        return false;
    }

    private void take(Node source, Node target) {
        Node a = commonAncestor(source, target);
        if (a == source || a == target) {
            a = a.parent;
        }
        Node activeLeaf = leaf;
        for (Node n = activeLeaf; n != a; n = n.parent) {
            if (n.history) {
                n.remembered = activeLeaf;
            }
            log.add("exit:" + n.name);
        }
        List<Node> path = new ArrayList<>();
        for (Node n = target; n != a; n = n.parent) {
            path.add(n);
        }
        Collections.reverse(path);
        for (int i = 0; i < path.size() - 1; i++) {
            log.add("enter:" + path.get(i).name);
        }
        enterSubtree(target);
    }

    /** Enters n itself and then descends to a leaf. */
    private void enterSubtree(Node n) {
        log.add("enter:" + n.name);
        descend(n);
    }

    private void descend(Node n) {
        if (n.children.isEmpty()) {
            leaf = n;
            return;
        }
        if (n.history && n.remembered != null) {
            List<Node> path = new ArrayList<>();
            for (Node x = n.remembered; x != n; x = x.parent) {
                path.add(x);
            }
            Collections.reverse(path);
            for (Node x : path) {
                log.add("enter:" + x.name);
            }
            leaf = n.remembered;
            return;
        }
        Node c = n.initial;
        log.add("enter:" + c.name);
        descend(c);
    }

    private static Node commonAncestor(Node s, Node t) {
        for (Node x = t; x != null; x = x.parent) {
            for (Node y = s; y != null; y = y.parent) {
                if (x == y) {
                    return x;
                }
            }
        }
        return null;
    }

    /** Builder for {@link StateMachine}. */
    public static final class Builder {
        private static final class Def {
            final String name;
            final String parent;
            String initial;
            boolean history;
            boolean hasChildren;

            Def(String name, String parent) {
                this.name = name;
                this.parent = parent;
            }
        }

        private record TDef(String source, String event, String target, BooleanSupplier guard) { }

        private final Map<String, Def> defs = new LinkedHashMap<>();
        private final List<TDef> transitions = new ArrayList<>();
        private String topInitial;

        private Builder() { }

        public Builder state(String name) {
            return state(name, null);
        }

        public Builder state(String name, String parent) {
            if (name == null || name.isEmpty() || defs.containsKey(name)) {
                throw new IllegalArgumentException("bad or duplicate state name: " + name);
            }
            Def p = null;
            if (parent != null) {
                p = defs.get(parent);
                if (p == null) {
                    throw new IllegalArgumentException("undeclared parent: " + parent);
                }
            }
            if (p != null) {
                p.hasChildren = true;
            }
            defs.put(name, new Def(name, parent));
            return this;
        }

        public Builder initial(String state) {
            Def d = declared(state);
            if (d.parent == null) {
                if (topInitial != null) {
                    throw new IllegalArgumentException("second top-level initial");
                }
                topInitial = state;
            } else {
                Def p = defs.get(d.parent);
                if (p.initial != null) {
                    throw new IllegalArgumentException("second initial for " + d.parent);
                }
                p.initial = state;
            }
            return this;
        }

        public Builder history(String composite) {
            declared(composite).history = true;
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return add(source, event, target, null);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            if (guard == null) {
                throw new IllegalArgumentException("guard is null");
            }
            return add(source, event, target, guard);
        }

        private Builder add(String source, String event, String target, BooleanSupplier guard) {
            declared(source);
            declared(target);
            if (event == null) {
                throw new IllegalArgumentException("event is null");
            }
            transitions.add(new TDef(source, event, target, guard));
            return this;
        }

        private Def declared(String name) {
            Def d = name == null ? null : defs.get(name);
            if (d == null) {
                throw new IllegalArgumentException("undeclared state: " + name);
            }
            return d;
        }

        public StateMachine build() {
            if (topInitial == null) {
                throw new IllegalStateException("no top-level initial state");
            }
            for (Def d : defs.values()) {
                if (d.hasChildren && d.initial == null) {
                    throw new IllegalStateException("composite without initial child: " + d.name);
                }
                if (!d.hasChildren && d.history) {
                    throw new IllegalStateException("history on a leaf: " + d.name);
                }
            }
            return new StateMachine(this);
        }
    }
}
