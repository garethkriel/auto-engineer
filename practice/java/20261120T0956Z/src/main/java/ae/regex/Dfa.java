package ae.regex;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.ArrayList;

/**
 * Complete deterministic automaton over character classes. The alphabet 0..0xFFFF is cut into intervals
 * {@code [points[i], points[i+1]-1]}; {@code next[state][class]} is total. State 0 is the start state.
 * {@link #minimize()} yields a canonical form: two DFAs accept the same language iff their canonical
 * forms are {@code equals}.
 */
public final class Dfa {
    private final int[] points;
    private final int[][] next;
    private final boolean[] accept;
    private final boolean[] dead;

    Dfa(int[] points, int[][] next, boolean[] accept) {
        if (points.length == 0 || points[0] != 0) {
            throw new IllegalArgumentException("first class must start at 0");
        }
        for (int i = 1; i < points.length; i++) {
            if (points[i] <= points[i - 1] || points[i] > CharSet.MAX) {
                throw new IllegalArgumentException("class starts must be strictly increasing within the alphabet");
            }
        }
        if (next.length != accept.length || next.length == 0) {
            throw new IllegalArgumentException("state table size mismatch");
        }
        for (int[] row : next) {
            if (row.length != points.length) {
                throw new IllegalArgumentException("row width must equal class count");
            }
            for (int t : row) {
                if (t < 0 || t >= next.length) {
                    throw new IllegalArgumentException("transition target out of range");
                }
            }
        }
        this.points = points;
        this.next = next;
        this.accept = accept;
        this.dead = computeDead();
    }

    /** A state is dead when no accepting state is reachable from it. */
    private boolean[] computeDead() {
        int n = next.length;
        boolean[] live = accept.clone();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int s = 0; s < n; s++) {
                if (live[s]) {
                    continue;
                }
                for (int t : next[s]) {
                    if (live[t]) {
                        live[s] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        boolean[] d = new boolean[n];
        for (int s = 0; s < n; s++) {
            d[s] = !live[s];
        }
        return d;
    }

    // ---- construction ----

    /** Subset construction; the empty subset becomes an explicit dead state if it is ever reached. */
    public static Dfa fromNfa(Nfa nfa) {
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        for (int s = 0; s < nfa.stateCount(); s++) {
            for (Nfa.Edge e : nfa.edgesOf(s)) {
                for (int i = 0; i < e.set().rangeCount(); i++) {
                    cuts.add(e.set().lo(i));
                    if (e.set().hi(i) < CharSet.MAX) {
                        cuts.add(e.set().hi(i) + 1);
                    }
                }
            }
        }
        int[] points = cuts.stream().mapToInt(Integer::intValue).toArray();
        Map<BitSet, Integer> ids = new HashMap<>();
        List<BitSet> sets = new ArrayList<>();
        List<int[]> rows = new ArrayList<>();
        BitSet init = new BitSet();
        init.set(nfa.start());
        init = nfa.closure(init);
        ids.put(init, 0);
        sets.add(init);
        for (int i = 0; i < sets.size(); i++) {
            BitSet cur = sets.get(i);
            int[] row = new int[points.length];
            for (int c = 0; c < points.length; c++) {
                BitSet moved = new BitSet();
                for (int s = cur.nextSetBit(0); s >= 0; s = cur.nextSetBit(s + 1)) {
                    for (Nfa.Edge e : nfa.edgesOf(s)) {
                        if (e.set().contains(points[c])) {
                            moved.set(e.to());
                        }
                    }
                }
                BitSet target = nfa.closure(moved);
                Integer id = ids.get(target);
                if (id == null) {
                    id = sets.size();
                    ids.put(target, id);
                    sets.add(target);
                }
                row[c] = id;
            }
            rows.add(row);
        }
        boolean[] acc = new boolean[sets.size()];
        for (int i = 0; i < acc.length; i++) {
            acc[i] = sets.get(i).get(nfa.accept());
        }
        return new Dfa(points, rows.toArray(new int[0][]), acc);
    }

    // ---- queries ----

    public int stateCount() {
        return next.length;
    }

    public int classCount() {
        return points.length;
    }

    private int classOf(int c) {
        int idx = Arrays.binarySearch(points, c);
        return idx >= 0 ? idx : -idx - 2;
    }

    private int classWidth(int cls) {
        int end = cls + 1 < points.length ? points[cls + 1] : CharSet.MAX + 1;
        return end - points[cls];
    }

    public boolean matches(CharSequence input) {
        int st = 0;
        for (int i = 0; i < input.length(); i++) {
            st = next[st][classOf(input.charAt(i))];
            if (dead[st]) {
                return false;
            }
        }
        return accept[st];
    }

    /** End index of the longest match starting exactly at {@code from}, or -1 if none. */
    public int longestMatchEnd(CharSequence text, int from) {
        int st = 0;
        int best = accept[0] ? from : -1;
        if (dead[0]) {
            return -1;
        }
        for (int i = from; i < text.length(); i++) {
            st = next[st][classOf(text.charAt(i))];
            if (dead[st]) {
                break;
            }
            if (accept[st]) {
                best = i + 1;
            }
        }
        return best;
    }

    /** A readable representative of a class: prefers a-z, 0-9, A-Z, then printable ASCII, else the class start. */
    private char pick(int cls) {
        int lo = points[cls];
        int hi = lo + classWidth(cls) - 1;
        int[][] preferred = {{'a', 'z'}, {'0', '9'}, {'A', 'Z'}, {' ', '~'}};
        for (int[] p : preferred) {
            if (Math.max(lo, p[0]) <= Math.min(hi, p[1])) {
                return (char) Math.max(lo, p[0]);
            }
        }
        return (char) lo;
    }

    /** Shortest accepted string (ties broken by class order), or empty if the language is empty. */
    public Optional<String> shortestAccepted() {
        int n = next.length;
        int[] parent = new int[n];
        int[] viaClass = new int[n];
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        parent[0] = -1;
        int goal = -1;
        while (!queue.isEmpty()) {
            int s = queue.poll();
            if (accept[s]) {
                goal = s;
                break;
            }
            for (int c = 0; c < points.length; c++) {
                int t = next[s][c];
                if (!seen[t]) {
                    seen[t] = true;
                    parent[t] = s;
                    viaClass[t] = c;
                    queue.add(t);
                }
            }
        }
        if (goal < 0) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (int s = goal; parent[s] >= 0; s = parent[s]) {
            sb.append(pick(viaClass[s]));
        }
        return Optional.of(sb.reverse().toString());
    }

    /** Number of accepted strings of exactly {@code length} chars. */
    public BigInteger count(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        BigInteger[] cur = new BigInteger[next.length];
        Arrays.fill(cur, BigInteger.ZERO);
        cur[0] = BigInteger.ONE;
        for (int step = 0; step < length; step++) {
            BigInteger[] nxt = new BigInteger[next.length];
            Arrays.fill(nxt, BigInteger.ZERO);
            for (int s = 0; s < next.length; s++) {
                if (cur[s].signum() == 0) {
                    continue;
                }
                for (int c = 0; c < points.length; c++) {
                    nxt[next[s][c]] = nxt[next[s][c]].add(cur[s].multiply(BigInteger.valueOf(classWidth(c))));
                }
            }
            cur = nxt;
        }
        BigInteger total = BigInteger.ZERO;
        for (int s = 0; s < next.length; s++) {
            if (accept[s]) {
                total = total.add(cur[s]);
            }
        }
        return total;
    }

    // ---- algebra ----

    public Dfa complement() {
        boolean[] flipped = new boolean[accept.length];
        for (int i = 0; i < flipped.length; i++) {
            flipped[i] = !accept[i];
        }
        return new Dfa(points, next, flipped);
    }

    public Dfa intersect(Dfa o) {
        return product(o, (x, y) -> x && y);
    }

    public Dfa union(Dfa o) {
        return product(o, (x, y) -> x || y);
    }

    public Dfa minus(Dfa o) {
        return product(o, (x, y) -> x && !y);
    }

    /** True iff both automata accept exactly the same language (decided by searching the symmetric difference). */
    public boolean equivalent(Dfa o) {
        return product(o, (x, y) -> x != y).shortestAccepted().isEmpty();
    }

    /** Shortest string accepted by exactly one of the two automata. */
    public Optional<String> distinguish(Dfa o) {
        return product(o, (x, y) -> x != y).shortestAccepted();
    }

    /** Reachable pair construction over the common refinement of both alphabets. */
    private Dfa product(Dfa o, BiPredicate<Boolean, Boolean> op) {
        TreeSet<Integer> cuts = new TreeSet<>();
        for (int p : points) {
            cuts.add(p);
        }
        for (int p : o.points) {
            cuts.add(p);
        }
        int[] pts = cuts.stream().mapToInt(Integer::intValue).toArray();
        int[] ca = new int[pts.length];
        int[] cb = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            ca[i] = classOf(pts[i]);
            cb[i] = o.classOf(pts[i]);
        }
        Map<Long, Integer> ids = new HashMap<>();
        List<int[]> pairs = new ArrayList<>();
        List<int[]> rows = new ArrayList<>();
        ids.put(0L, 0);
        pairs.add(new int[] {0, 0});
        long width = o.next.length;
        for (int i = 0; i < pairs.size(); i++) {
            int[] pr = pairs.get(i);
            int[] row = new int[pts.length];
            for (int c = 0; c < pts.length; c++) {
                int ta = next[pr[0]][ca[c]];
                int tb = o.next[pr[1]][cb[c]];
                long key = ta * width + tb;
                Integer id = ids.get(key);
                if (id == null) {
                    id = pairs.size();
                    ids.put(key, id);
                    pairs.add(new int[] {ta, tb});
                }
                row[c] = id;
            }
            rows.add(row);
        }
        boolean[] acc = new boolean[pairs.size()];
        for (int i = 0; i < acc.length; i++) {
            acc[i] = op.test(accept[pairs.get(i)[0]], o.accept[pairs.get(i)[1]]);
        }
        return new Dfa(pts, rows.toArray(new int[0][]), acc);
    }

    // ---- minimisation to canonical form ----

    /** Moore partition refinement, merging of adjacent identical classes, then BFS renumbering from the start state. */
    public Dfa minimize() {
        int n = next.length;
        int[] part = new int[n];
        for (int s = 0; s < n; s++) {
            part[s] = accept[s] ? 1 : 0;
        }
        int count = -1;
        while (true) {
            Map<List<Integer>, Integer> sig = new HashMap<>();
            int[] np = new int[n];
            for (int s = 0; s < n; s++) {
                List<Integer> key = new ArrayList<>(points.length + 1);
                key.add(part[s]);
                for (int c = 0; c < points.length; c++) {
                    key.add(part[next[s][c]]);
                }
                Integer id = sig.get(key);
                if (id == null) {
                    id = sig.size();
                    sig.put(key, id);
                }
                np[s] = id;
            }
            part = np;
            if (sig.size() == count) {
                break;
            }
            count = sig.size();
        }
        // quotient automaton (every state is reachable by construction of all builders in this class)
        int[] repr = new int[count];
        Arrays.fill(repr, -1);
        for (int s = 0; s < n; s++) {
            if (repr[part[s]] < 0) {
                repr[part[s]] = s;
            }
        }
        int[][] qn = new int[count][points.length];
        boolean[] qa = new boolean[count];
        for (int p = 0; p < count; p++) {
            qa[p] = accept[repr[p]];
            for (int c = 0; c < points.length; c++) {
                qn[p][c] = part[next[repr[p]][c]];
            }
        }
        int start = part[0];
        // merge adjacent classes whose columns are identical
        List<Integer> keep = new ArrayList<>();
        keep.add(0);
        for (int c = 1; c < points.length; c++) {
            boolean same = true;
            for (int p = 0; p < count && same; p++) {
                same = qn[p][c] == qn[p][c - 1];
            }
            if (!same) {
                keep.add(c);
            }
        }
        int[] mp = new int[keep.size()];
        for (int i = 0; i < mp.length; i++) {
            mp[i] = points[keep.get(i)];
        }
        // BFS renumbering from the start state, scanning classes in order
        int[] newId = new int[count];
        Arrays.fill(newId, -1);
        List<Integer> order = new ArrayList<>();
        newId[start] = 0;
        order.add(start);
        for (int i = 0; i < order.size(); i++) {
            for (int c : keep) {
                int t = qn[order.get(i)][c];
                if (newId[t] < 0) {
                    newId[t] = order.size();
                    order.add(t);
                }
            }
        }
        int[][] fn = new int[order.size()][mp.length];
        boolean[] fa = new boolean[order.size()];
        for (int i = 0; i < order.size(); i++) {
            fa[i] = qa[order.get(i)];
            for (int k = 0; k < mp.length; k++) {
                fn[i][k] = newId[qn[order.get(i)][keep.get(k)]];
            }
        }
        return new Dfa(mp, fn, fa);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dfa d && Arrays.equals(points, d.points) && Arrays.deepEquals(next, d.next)
                && Arrays.equals(accept, d.accept);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(points) + Arrays.deepHashCode(next)) + Arrays.hashCode(accept);
    }

    @Override
    public String toString() {
        return "Dfa[states=" + next.length + ", classes=" + points.length + "]";
    }
}
