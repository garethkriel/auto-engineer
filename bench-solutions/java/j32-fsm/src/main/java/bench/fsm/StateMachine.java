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
    private record Trans(String event, String target, BooleanSupplier guard) {}

    private final Map<String, String> parent;
    private final Map<String, String> initialChild;
    private final String topInitial;
    private final Set<String> history;
    private final Map<String, List<Trans>> trans;
    private final Map<String, String> remembered = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private String leaf;

    private StateMachine(Builder b) {
        parent = new HashMap<>(b.parent);
        initialChild = new HashMap<>(b.initialChild);
        topInitial = b.topInitial;
        history = new HashSet<>(b.history);
        trans = new HashMap<>();
        for (var e : b.trans.entrySet()) trans.put(e.getKey(), new ArrayList<>(e.getValue()));
        // enter the top-level initial state
        enterTarget(topInitial);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String current() {
        return leaf;
    }

    public List<String> active() {
        List<String> path = new ArrayList<>();
        for (String s = leaf; s != null; s = parent.get(s)) path.add(s);
        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    public List<String> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public boolean fire(String event) {
        if (event == null) throw new IllegalArgumentException("event");
        for (String s = leaf; s != null; s = parent.get(s)) {
            List<Trans> ts = trans.get(s);
            if (ts == null) continue;
            for (Trans t : ts) {
                if (!t.event.equals(event)) continue;
                if (t.guard != null && !t.guard.getAsBoolean()) continue;
                take(s, t.target);
                return true;
            }
        }
        return false;
    }

    private List<String> chain(String s) {
        List<String> c = new ArrayList<>();
        for (String x = s; x != null; x = parent.get(x)) c.add(x);
        return c;
    }

    private void take(String source, String target) {
        List<String> cs = chain(source);
        Set<String> inS = new HashSet<>(cs);
        String a = null;
        for (String x : chain(target)) {
            if (inS.contains(x)) {
                a = x;
                break;
            }
        }
        if (a != null && (a.equals(source) || a.equals(target))) a = parent.get(a);
        // exit from the active leaf up to (not including) a
        String currentLeaf = leaf;
        for (String x = currentLeaf; x != null && !x.equals(a); x = parent.get(x)) {
            if (history.contains(x)) remembered.put(x, currentLeaf);
            log.add("exit:" + x);
        }
        // enter from just below a down to target
        List<String> down = new ArrayList<>();
        for (String x = target; x != null && !x.equals(a); x = parent.get(x)) down.add(x);
        Collections.reverse(down);
        for (int i = 0; i < down.size() - 1; i++) {
            log.add("enter:" + down.get(i));
            leaf = down.get(i);
        }
        enterTarget(target);
    }

    /** Enter state s (logging it) and then descend to a leaf. */
    private void enterTarget(String s) {
        log.add("enter:" + s);
        leaf = s;
        descend(s);
    }

    private void descend(String s) {
        String cur = s;
        while (initialChild.containsKey(cur) || isComposite(cur)) {
            String remLeaf = history.contains(cur) ? remembered.get(cur) : null;
            if (remLeaf != null) {
                List<String> path = new ArrayList<>();
                for (String x = remLeaf; x != null && !x.equals(cur); x = parent.get(x)) path.add(x);
                Collections.reverse(path);
                for (String x : path) log.add("enter:" + x);
                leaf = remLeaf;
                return;
            }
            String next = initialChild.get(cur);
            log.add("enter:" + next);
            leaf = next;
            cur = next;
        }
    }

    private boolean isComposite(String s) {
        return initialChild.containsKey(s);
    }

    public static final class Builder {
        private final Map<String, String> parent = new LinkedHashMap<>();
        private final Set<String> declared = new HashSet<>();
        private final Set<String> hasChildren = new HashSet<>();
        private final Map<String, String> initialChild = new HashMap<>();
        private String topInitial;
        private final Set<String> history = new HashSet<>();
        private final Map<String, List<Trans>> trans = new HashMap<>();

        private Builder() {}

        private void checkNew(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name");
            if (declared.contains(name)) throw new IllegalArgumentException("duplicate " + name);
        }

        private void checkDeclared(String name) {
            if (name == null || !declared.contains(name)) throw new IllegalArgumentException("undeclared " + name);
        }

        public Builder state(String name) {
            checkNew(name);
            declared.add(name);
            return this;
        }

        public Builder state(String name, String parentName) {
            checkNew(name);
            checkDeclared(parentName);
            declared.add(name);
            parent.put(name, parentName);
            hasChildren.add(parentName);
            return this;
        }

        public Builder initial(String state) {
            checkDeclared(state);
            String p = parent.get(state);
            if (p == null) {
                if (topInitial != null) throw new IllegalArgumentException("second top-level initial");
                topInitial = state;
            } else {
                if (initialChild.containsKey(p)) throw new IllegalArgumentException("second initial for " + p);
                initialChild.put(p, state);
            }
            return this;
        }

        public Builder history(String composite) {
            checkDeclared(composite);
            history.add(composite);
            return this;
        }

        public Builder transition(String source, String event, String target) {
            return transition(source, event, target, () -> true, false);
        }

        public Builder transition(String source, String event, String target, BooleanSupplier guard) {
            if (guard == null) throw new IllegalArgumentException("guard");
            return transition(source, event, target, guard, true);
        }

        private Builder transition(String source, String event, String target, BooleanSupplier guard, boolean g) {
            checkDeclared(source);
            checkDeclared(target);
            if (event == null) throw new IllegalArgumentException("event");
            trans.computeIfAbsent(source, k -> new ArrayList<>()).add(new Trans(event, target, g ? guard : null));
            return this;
        }

        public StateMachine build() {
            if (topInitial == null) throw new IllegalStateException("no initial state");
            for (String c : hasChildren) {
                if (!initialChild.containsKey(c)) throw new IllegalStateException("no initial child for " + c);
            }
            for (String h : history) {
                if (!hasChildren.contains(h)) throw new IllegalStateException("history on leaf " + h);
            }
            return new StateMachine(this);
        }
    }
}
