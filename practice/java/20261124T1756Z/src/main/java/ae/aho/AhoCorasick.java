package ae.aho;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Immutable Aho-Corasick automaton over UTF-16 chars.
 *
 * <p>Nodes are numbered in creation order, node 0 is the root. Children are stored as sorted
 * key arrays and looked up by binary search; failure and output links are computed breadth first.
 * Matching is amortised linear in the text length plus the number of reported matches.
 */
public final class AhoCorasick {

    /** Receives matches while walking a text. */
    @FunctionalInterface
    private interface Sink {
        void accept(int start, int end, int pattern);
    }

    private final boolean ignoreCase;
    private final int[] patternLength;

    // Trie, indexed by node.
    private final char[][] keys;
    private final int[][] targets;
    final int[] depth;
    final int[] parent;
    final int[] fail;
    final int[] out;
    private final int[][] endsHere;

    private AhoCorasick(boolean ignoreCase, int[] patternLength, char[][] keys, int[][] targets,
                        int[] depth, int[] parent, int[] fail, int[] out, int[][] endsHere) {
        this.ignoreCase = ignoreCase;
        this.patternLength = patternLength;
        this.keys = keys;
        this.targets = targets;
        this.depth = depth;
        this.parent = parent;
        this.fail = fail;
        this.out = out;
        this.endsHere = endsHere;
    }

    private static char norm(char c, boolean ignoreCase) {
        return ignoreCase ? Character.toLowerCase(Character.toUpperCase(c)) : c;
    }

    /**
     * Builds an automaton.
     *
     * @throws IllegalArgumentException if the list is null or a pattern is null or empty
     */
    public static AhoCorasick build(List<String> patterns, boolean ignoreCase) {
        if (patterns == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        int n = patterns.size();
        int[] lengths = new int[n];
        List<TreeMap<Character, Integer>> children = new ArrayList<>();
        List<Integer> depthList = new ArrayList<>();
        List<Integer> parentList = new ArrayList<>();
        List<List<Integer>> ends = new ArrayList<>();
        children.add(new TreeMap<>());
        depthList.add(0);
        parentList.add(0);
        ends.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            String p = patterns.get(i);
            if (p == null || p.isEmpty()) {
                throw new IllegalArgumentException("pattern " + i + " is null or empty");
            }
            lengths[i] = p.length();
            int node = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = norm(p.charAt(k), ignoreCase);
                Integer next = children.get(node).get(c);
                if (next == null) {
                    next = children.size();
                    children.get(node).put(c, next);
                    children.add(new TreeMap<>());
                    depthList.add(depthList.get(node) + 1);
                    parentList.add(node);
                    ends.add(new ArrayList<>());
                }
                node = next;
            }
            ends.get(node).add(i);
        }
        int m = children.size();
        char[][] keys = new char[m][];
        int[][] targets = new int[m][];
        int[][] endsHere = new int[m][];
        int[] depth = new int[m];
        int[] parent = new int[m];
        for (int v = 0; v < m; v++) {
            TreeMap<Character, Integer> map = children.get(v);
            keys[v] = new char[map.size()];
            targets[v] = new int[map.size()];
            int j = 0;
            for (var e : map.entrySet()) {
                keys[v][j] = e.getKey();
                targets[v][j] = e.getValue();
                j++;
            }
            endsHere[v] = ends.get(v).stream().mapToInt(Integer::intValue).toArray();
            depth[v] = depthList.get(v);
            parent[v] = parentList.get(v);
        }
        int[] fail = new int[m];
        int[] out = new int[m];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int t : targets[0]) {
            fail[t] = 0;
            out[t] = 0;
            queue.add(t);
        }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int j = 0; j < keys[v].length; j++) {
                char c = keys[v][j];
                int child = targets[v][j];
                int f = fail[v];
                int step = child(keys, targets, f, c);
                while (step < 0 && f != 0) {
                    f = fail[f];
                    step = child(keys, targets, f, c);
                }
                fail[child] = step < 0 ? 0 : step;
                out[child] = endsHere[fail[child]].length > 0 ? fail[child] : out[fail[child]];
                queue.add(child);
            }
        }
        return new AhoCorasick(ignoreCase, lengths, keys, targets, depth, parent, fail, out, endsHere);
    }

    private static int child(char[][] keys, int[][] targets, int node, char c) {
        int idx = Arrays.binarySearch(keys[node], c);
        return idx < 0 ? -1 : targets[node][idx];
    }

    /** Node reached from {@code node} after reading {@code raw} (following failure links). */
    private int step(int node, char raw) {
        char c = norm(raw, ignoreCase);
        int v = node;
        int next = child(keys, targets, v, c);
        while (next < 0 && v != 0) {
            v = fail[v];
            next = child(keys, targets, v, c);
        }
        return next < 0 ? 0 : next;
    }

    /** Reports every match ending at the position just consumed (end = exclusive end index). */
    private void emit(int node, int end, Sink sink) {
        int u = endsHere[node].length > 0 ? node : out[node];
        while (u != 0) {
            for (int p : endsHere[u]) {
                sink.accept(end - patternLength[p], end, p);
            }
            u = out[u];
        }
    }

    private void walk(CharSequence text, Sink sink) {
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            node = step(node, text.charAt(i));
            emit(node, i + 1, sink);
        }
    }

    public int patternCount() {
        return patternLength.length;
    }

    public int nodeCount() {
        return keys.length;
    }

    /** All occurrences, overlaps included, sorted by (end, start, pattern). */
    public List<Match> findAll(CharSequence text) {
        List<Match> result = new ArrayList<>();
        walk(text, (s, e, p) -> result.add(new Match(s, e, p)));
        return result;
    }

    public int[] countPerPattern(CharSequence text) {
        int[] counts = new int[patternLength.length];
        walk(text, (s, e, p) -> counts[p]++);
        return counts;
    }

    /** Non-overlapping scan from the left, taking the longest match at each start. */
    public List<Match> leftmostLongest(CharSequence text) {
        int len = text.length();
        int[] bestEnd = new int[len];
        int[] bestPattern = new int[len];
        Arrays.fill(bestEnd, -1);
        walk(text, (s, e, p) -> {
            if (e > bestEnd[s] || (e == bestEnd[s] && p < bestPattern[s])) {
                bestEnd[s] = e;
                bestPattern[s] = p;
            }
        });
        List<Match> result = new ArrayList<>();
        int i = 0;
        while (i < len) {
            if (bestEnd[i] >= 0) {
                result.add(new Match(i, bestEnd[i], bestPattern[i]));
                i = bestEnd[i];
            } else {
                i++;
            }
        }
        return result;
    }

    /** Replaces the {@link #leftmostLongest} matches; replacement i belongs to pattern i. */
    public String replaceAll(CharSequence text, List<String> replacements) {
        if (replacements == null || replacements.size() != patternLength.length) {
            throw new IllegalArgumentException("need exactly " + patternLength.length + " replacements");
        }
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (Match m : leftmostLongest(text)) {
            sb.append(text, last, m.start()).append(replacements.get(m.pattern()));
            last = m.end();
        }
        return sb.append(text, last, text.length()).toString();
    }

    public Stream stream() {
        return new Stream();
    }

    /** Stateful matcher: feed the text in arbitrary chunks, get absolute-offset matches. */
    public final class Stream {
        private int node;
        private long position;

        private Stream() {
        }

        public List<Match> feed(CharSequence chunk) {
            List<Match> result = new ArrayList<>();
            for (int i = 0; i < chunk.length(); i++) {
                node = step(node, chunk.charAt(i));
                position++;
                long end = position;
                emit(node, 0, (s, e, p) -> {
                    int length = patternLength[p];
                    result.add(new Match(Math.toIntExact(end - length), Math.toIntExact(end), p));
                });
            }
            return result;
        }

        public long position() {
            return position;
        }

        public void reset() {
            node = 0;
            position = 0;
        }
    }

    /** Structural self-check; throws IllegalStateException describing the first violation. */
    void checkInvariants() {
        int m = keys.length;
        for (int v = 0; v < m; v++) {
            for (int j = 0; j < keys[v].length; j++) {
                int c = targets[v][j];
                if (parent[c] != v || depth[c] != depth[v] + 1) {
                    throw new IllegalStateException("bad parent/depth at node " + c);
                }
                if (j > 0 && keys[v][j - 1] >= keys[v][j]) {
                    throw new IllegalStateException("keys not strictly sorted at node " + v);
                }
            }
            if (v == 0) {
                continue;
            }
            if (depth[v] == 1 && fail[v] != 0) {
                throw new IllegalStateException("depth-1 node " + v + " must fail to root");
            }
            if (depth[fail[v]] >= depth[v]) {
                throw new IllegalStateException("fail link of " + v + " is not shallower");
            }
            int expected = endsHere[fail[v]].length > 0 ? fail[v] : out[fail[v]];
            if (out[v] != expected) {
                throw new IllegalStateException("bad output link at node " + v);
            }
            if (!isSuffixPath(v, fail[v])) {
                throw new IllegalStateException("fail link of " + v + " is not a suffix");
            }
        }
        for (int v = 0; v < m; v++) {
            for (int p : endsHere[v]) {
                if (depth[v] != patternLength[p]) {
                    throw new IllegalStateException("pattern " + p + " ends at wrong depth");
                }
            }
        }
    }

    /** True if the string of {@code suffixNode} is a suffix of the string of {@code node}. */
    private boolean isSuffixPath(int node, int suffixNode) {
        char[] a = path(node);
        char[] b = path(suffixNode);
        if (b.length > a.length) {
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            if (a[a.length - b.length + i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private char[] path(int node) {
        char[] chars = new char[depth[node]];
        int v = node;
        while (v != 0) {
            int p = parent[v];
            int idx = -1;
            for (int j = 0; j < targets[p].length; j++) {
                if (targets[p][j] == v) {
                    idx = j;
                }
            }
            chars[depth[v] - 1] = keys[p][idx];
            v = p;
        }
        return chars;
    }
}
