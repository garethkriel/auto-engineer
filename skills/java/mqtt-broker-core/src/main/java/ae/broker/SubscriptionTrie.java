package ae.broker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps topic filters to (key, value) subscriptions and finds all subscriptions matching a topic. */
public final class SubscriptionTrie<V> {
    /** One matching subscription. */
    public record Entry<V>(String key, V value) {}

    private static final class Node<V> {
        final Map<String, Node<V>> children = new HashMap<>();
        final Map<String, V> subs = new HashMap<>();

        boolean isEmpty() {
            return children.isEmpty() && subs.isEmpty();
        }
    }

    private final Node<V> root = new Node<>();
    private int size;

    /** Adds or replaces the subscription of {@code key} on {@code filter}. */
    public void add(String filter, String key, V value) {
        Topics.validateFilter(filter);
        Node<V> n = root;
        for (String level : filter.split("/", -1)) {
            n = n.children.computeIfAbsent(level, k -> new Node<>());
        }
        if (n.subs.put(key, value) == null) {
            size++;
        }
    }

    /** Removes one subscription; prunes nodes left empty. Returns whether it existed. */
    public boolean remove(String filter, String key) {
        String[] levels = filter.split("/", -1);
        List<Node<V>> path = new ArrayList<>();
        Node<V> n = root;
        path.add(n);
        for (String level : levels) {
            n = n.children.get(level);
            if (n == null) {
                return false;
            }
            path.add(n);
        }
        if (n.subs.remove(key) == null) {
            return false;
        }
        size--;
        for (int i = levels.length; i > 0 && path.get(i).isEmpty(); i--) {
            path.get(i - 1).children.remove(levels[i - 1]);
        }
        return true;
    }

    /** Removes every subscription of {@code key}; returns how many. */
    public int removeKey(String key) {
        int before = size;
        List<String> filters = new ArrayList<>();
        collectFilters(root, "", true, key, filters);
        for (String f : filters) {
            remove(f, key);
        }
        return before - size;
    }

    private void collectFilters(Node<V> n, String prefix, boolean atRoot, String key, List<String> out) {
        if (n.subs.containsKey(key)) {
            out.add(prefix);
        }
        for (Map.Entry<String, Node<V>> e : n.children.entrySet()) {
            collectFilters(e.getValue(), atRoot ? e.getKey() : prefix + "/" + e.getKey(), false, key, out);
        }
    }

    /** All subscriptions whose filter matches {@code topic} (one entry per filter). */
    public List<Entry<V>> match(String topic) {
        List<Entry<V>> out = new ArrayList<>();
        String[] levels = topic.split("/", -1);
        collect(root, levels, 0, topic.startsWith("$"), out);
        return out;
    }

    private void collect(Node<V> n, String[] levels, int i, boolean dollar, List<Entry<V>> out) {
        boolean wildcardsAllowed = !(i == 0 && dollar);
        if (wildcardsAllowed) {
            Node<V> hash = n.children.get("#");
            if (hash != null) {
                addAll(hash, out);
            }
        }
        if (i == levels.length) {
            addAll(n, out);
            return;
        }
        Node<V> lit = n.children.get(levels[i]);
        if (lit != null) {
            collect(lit, levels, i + 1, dollar, out);
        }
        if (wildcardsAllowed) {
            Node<V> plus = n.children.get("+");
            if (plus != null) {
                collect(plus, levels, i + 1, dollar, out);
            }
        }
    }

    private void addAll(Node<V> n, List<Entry<V>> out) {
        for (Map.Entry<String, V> e : n.subs.entrySet()) {
            out.add(new Entry<>(e.getKey(), e.getValue()));
        }
    }

    /** Number of (filter, key) subscriptions. */
    public int size() {
        return size;
    }

    /** Number of trie nodes including the root (for pruning tests). */
    public int nodeCount() {
        return count(root);
    }

    private int count(Node<V> n) {
        int c = 1;
        for (Node<V> ch : n.children.values()) {
            c += count(ch);
        }
        return c;
    }
}
