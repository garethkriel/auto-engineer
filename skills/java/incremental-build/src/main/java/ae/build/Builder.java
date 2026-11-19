package ae.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Incremental build engine with dynamic dependencies and early cutoff.
 *
 * <p>A rule is rerun only if the stored record is missing or one of its recorded dependencies now has
 * a different value hash (checked in recorded order, stopping at the first difference). Not thread
 * safe: use one builder from one thread.
 */
public final class Builder {

    private final Store store;
    private final Map<String, String> inputs = new HashMap<>();
    private final Map<String, Task> rules = new HashMap<>();

    private List<String> lastExecuted = List.of();
    private List<String> lastReused = List.of();

    public Builder() {
        this(new Store());
    }

    public Builder(Store store) {
        this.store = Objects.requireNonNull(store);
    }

    public Store store() {
        return store;
    }

    /** Sets (or changes) a leaf input value. */
    public void input(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (rules.containsKey(key)) {
            throw new IllegalArgumentException("key is a rule: " + key);
        }
        inputs.put(key, value);
    }

    /** Registers (or replaces) the rule for {@code key}. */
    public void rule(String key, Task task) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(task);
        if (inputs.containsKey(key)) {
            throw new IllegalArgumentException("key is an input: " + key);
        }
        rules.put(key, task);
    }

    public String build(String key) {
        return build(List.of(Objects.requireNonNull(key))).get(0);
    }

    /** One run building all keys in order; work is shared between them. */
    public List<String> build(List<String> keys) {
        Run run = new Run();
        List<String> out = new ArrayList<>();
        try {
            for (String k : keys) {
                out.add(run.resolve(Objects.requireNonNull(k)).value);
            }
        } finally {
            lastExecuted = List.copyOf(run.executed);
            lastReused = List.copyOf(run.reused);
        }
        return out;
    }

    /** Rules that ran in the last run, in completion order. */
    public List<String> lastExecuted() {
        return lastExecuted;
    }

    /** Rules verified clean (not run) in the last run, in completion order. */
    public List<String> lastReused() {
        return lastReused;
    }

    /** Removes records not reachable from {@code roots} through recorded deps; returns how many. */
    public int prune(Collection<String> roots) {
        Set<String> live = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String k = todo.pop();
            Store.RecordView rec = store.record(k);
            if (rec != null && live.add(k)) {
                todo.addAll(rec.depKeys());
            }
        }
        int removed = 0;
        for (String k : store.keys()) {
            if (!live.contains(k) && store.remove(k)) {
                removed++;
            }
        }
        return removed;
    }

    static String hash(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Outcome of resolving one key within a run. */
    private record Resolved(String value, String hash) { }

    private final class Run {
        final Map<String, Resolved> done = new HashMap<>();
        final Map<String, BuildException> failed = new HashMap<>();
        final Deque<String> stack = new ArrayDeque<>();
        final Set<String> onStack = new HashSet<>();
        final List<String> executed = new ArrayList<>();
        final List<String> reused = new ArrayList<>();

        Resolved resolve(String key) {
            Resolved r = done.get(key);
            if (r != null) {
                return r;
            }
            BuildException f = failed.get(key);
            if (f != null) {
                throw f;
            }
            if (onStack.contains(key)) {
                List<String> path = new ArrayList<>();
                boolean in = false;
                // stack iterates from the most recent entry; collect oldest-first from the loop start
                List<String> oldestFirst = new ArrayList<>(stack);
                java.util.Collections.reverse(oldestFirst);
                for (String s : oldestFirst) {
                    in |= s.equals(key);
                    if (in) {
                        path.add(s);
                    }
                }
                path.add(key);
                throw new CycleException(path);
            }
            stack.push(key);
            onStack.add(key);
            try {
                r = compute(key);
                done.put(key, r);
                return r;
            } catch (BuildException e) {
                failed.putIfAbsent(key, e);
                throw e;
            } finally {
                stack.pop();
                onStack.remove(key);
            }
        }

        private Resolved compute(String key) {
            String in = inputs.get(key);
            if (in != null) {
                return new Resolved(in, hash(in));
            }
            Task task = rules.get(key);
            if (task == null) {
                throw new BuildException(key, "no rule or input for key: " + key, null);
            }
            Store.RecordView rec = store.record(key);
            if (rec != null && verified(rec)) {
                reused.add(key);
                return new Resolved(rec.value(), rec.hash());
            }
            return execute(key, task);
        }

        /** True if every recorded dep still has the recorded hash; a dep that fails counts as changed. */
        private boolean verified(Store.RecordView rec) {
            for (Store.Dep d : rec.deps()) {
                Resolved dep;
                try {
                    dep = resolve(d.key());
                } catch (BuildException e) {
                    return false;
                }
                if (!dep.hash.equals(d.hash())) {
                    return false;
                }
            }
            return true;
        }

        private Resolved execute(String key, Task task) {
            TaskContext ctx = new TaskContext();
            String value;
            try {
                value = task.run(ctx);
            } catch (BuildException e) {
                throw e;
            } catch (Exception e) {
                throw new BuildException(key, "task failed: " + key + ": " + e, e);
            } finally {
                ctx.open = false;
                executed.add(key);
            }
            if (value == null) {
                throw new BuildException(key, "task returned null: " + key, new IllegalStateException("null result"));
            }
            String h = hash(value);
            List<Store.Dep> deps = new ArrayList<>();
            ctx.needed.forEach((k, dh) -> deps.add(new Store.Dep(k, dh)));
            store.put(key, new Store.RecordView(value, h, List.copyOf(deps)));
            return new Resolved(value, h);
        }

        private final class TaskContext implements Context {
            final Map<String, String> needed = new LinkedHashMap<>();
            boolean open = true;

            @Override
            public String need(String key) {
                if (!open) {
                    throw new IllegalStateException("context used after its task finished");
                }
                Resolved r = resolve(Objects.requireNonNull(key));
                needed.putIfAbsent(key, r.hash);
                return r.value;
            }
        }
    }

}
