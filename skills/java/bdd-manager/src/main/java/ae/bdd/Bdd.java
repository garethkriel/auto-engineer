package ae.bdd;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A manager for reduced ordered binary decision diagrams.
 *
 * <p>Nodes are plain ints: {@link #FALSE} (0), {@link #TRUE} (1) and non-terminals with a
 * variable index, a low child (variable false) and a high child (variable true). Variable
 * {@code i} sits at level {@code i}: variable 0 is nearest the root. A unique table guarantees
 * canonicity, so two functions are equal exactly when their node ids are equal.
 *
 * <p>There is no garbage collection; a manager is meant to be short-lived. Creating more than
 * {@code maxNodes} nodes (terminals included) throws {@link IllegalStateException}. Not thread safe.
 */
public final class Bdd {
    public static final int FALSE = 0;
    public static final int TRUE = 1;

    private static final int TERMINAL_VAR = Integer.MAX_VALUE;

    private record Key(int var, int lo, int hi) {}

    private record OpKey(int f, int g, int h) {}

    private final int numVars;
    private final int maxNodes;
    private int[] vars = new int[16];
    private int[] los = new int[16];
    private int[] his = new int[16];
    private int count;
    private final Map<Key, Integer> unique = new HashMap<>();
    private final Map<OpKey, Integer> iteCache = new HashMap<>();

    public Bdd(int numVars, int maxNodes) {
        if (numVars < 0) throw new IllegalArgumentException("numVars < 0");
        if (maxNodes < 2) throw new IllegalArgumentException("maxNodes < 2");
        this.numVars = numVars;
        this.maxNodes = maxNodes;
        append(TERMINAL_VAR, 0, 0);
        append(TERMINAL_VAR, 1, 1);
    }

    public int numVars() {
        return numVars;
    }

    /** Number of nodes ever created, terminals included. */
    public int nodeCount() {
        return count;
    }

    private void append(int var, int lo, int hi) {
        if (count == vars.length) {
            int n = count * 2;
            vars = Arrays.copyOf(vars, n);
            los = Arrays.copyOf(los, n);
            his = Arrays.copyOf(his, n);
        }
        vars[count] = var;
        los[count] = lo;
        his[count] = hi;
        count++;
    }

    private int mk(int var, int lo, int hi) {
        if (lo == hi) return lo;
        Key key = new Key(var, lo, hi);
        Integer found = unique.get(key);
        if (found != null) return found;
        if (count >= maxNodes) throw new IllegalStateException("node limit " + maxNodes + " reached");
        int id = count;
        append(var, lo, hi);
        unique.put(key, id);
        return id;
    }

    private void node(int f) {
        if (f < 0 || f >= count) throw new IllegalArgumentException("not a node: " + f);
    }

    private void variable(int v) {
        if (v < 0 || v >= numVars) throw new IllegalArgumentException("variable out of range: " + v);
    }

    private int level(int f) {
        return vars[f];
    }

    // ---- construction -----------------------------------------------------

    public int var(int i) {
        variable(i);
        return mk(i, FALSE, TRUE);
    }

    public int nvar(int i) {
        variable(i);
        return mk(i, TRUE, FALSE);
    }

    public int not(int f) {
        return ite(f, FALSE, TRUE);
    }

    public int and(int f, int g) {
        return ite(f, g, FALSE);
    }

    public int or(int f, int g) {
        return ite(f, TRUE, g);
    }

    public int xor(int f, int g) {
        node(g);
        return ite(f, not(g), g);
    }

    public int imp(int f, int g) {
        return ite(f, g, TRUE);
    }

    public int iff(int f, int g) {
        node(g);
        return ite(f, g, not(g));
    }

    /** If-then-else: {@code (f & g) | (!f & h)}. */
    public int ite(int f, int g, int h) {
        node(f);
        node(g);
        node(h);
        return iteRec(f, g, h);
    }

    private int iteRec(int f, int g, int h) {
        if (f == TRUE) return g;
        if (f == FALSE) return h;
        if (g == h) return g;
        if (g == TRUE && h == FALSE) return f;
        // f is a non-terminal here; ite(f, f, h) = ite(f, 1, h) and ite(f, g, f) = ite(f, g, 0)
        if (g == f) g = TRUE;
        if (h == f) h = FALSE;
        if (g == h) return g;
        if (g == TRUE && h == FALSE) return f;
        OpKey key = new OpKey(f, g, h);
        Integer cached = iteCache.get(key);
        if (cached != null) return cached;
        int v = Math.min(level(f), Math.min(level(g), level(h)));
        int lo = iteRec(cofactor(f, v, false), cofactor(g, v, false), cofactor(h, v, false));
        int hi = iteRec(cofactor(f, v, true), cofactor(g, v, true), cofactor(h, v, true));
        int r = mk(v, lo, hi);
        iteCache.put(key, r);
        return r;
    }

    /** Cofactor of {@code f} with respect to the variable at level {@code v}, which is at or above f's top. */
    private int cofactor(int f, int v, boolean value) {
        if (level(f) != v) return f;
        return value ? his[f] : los[f];
    }

    // ---- derived operations ------------------------------------------------

    public int restrict(int f, int v, boolean value) {
        node(f);
        variable(v);
        return restrictRec(f, v, value, new HashMap<>());
    }

    private int restrictRec(int f, int v, boolean value, Map<Integer, Integer> memo) {
        if (level(f) > v) return f; // also covers terminals
        if (level(f) == v) return value ? his[f] : los[f];
        Integer m = memo.get(f);
        if (m != null) return m;
        int r = mk(level(f), restrictRec(los[f], v, value, memo), restrictRec(his[f], v, value, memo));
        memo.put(f, r);
        return r;
    }

    public int exists(int f, int... quantified) {
        node(f);
        return quantify(f, quantified, true);
    }

    public int forall(int f, int... quantified) {
        node(f);
        return quantify(f, quantified, false);
    }

    private int quantify(int f, int[] vs, boolean existential) {
        BitSet set = new BitSet(numVars);
        for (int v : vs) {
            variable(v);
            set.set(v);
        }
        return quantRec(f, set, existential, new HashMap<>());
    }

    private int quantRec(int f, BitSet set, boolean existential, Map<Integer, Integer> memo) {
        if (f <= TRUE) return f;
        Integer m = memo.get(f);
        if (m != null) return m;
        int lo = quantRec(los[f], set, existential, memo);
        int hi = quantRec(his[f], set, existential, memo);
        int r;
        if (set.get(level(f))) r = existential ? or(lo, hi) : and(lo, hi);
        else r = mk(level(f), lo, hi);
        memo.put(f, r);
        return r;
    }

    /** Substitutes function {@code g} for variable {@code v} in {@code f}. */
    public int compose(int f, int v, int g) {
        node(f);
        node(g);
        variable(v);
        return ite(g, restrict(f, v, true), restrict(f, v, false));
    }

    /** Relational product: {@code exists vars. (f & g)}. */
    public int andExists(int f, int g, int... quantified) {
        node(f);
        node(g);
        return quantify(and(f, g), quantified, true);
    }

    // ---- queries -----------------------------------------------------------

    public boolean eval(int f, boolean[] assignment) {
        node(f);
        if (assignment.length != numVars) throw new IllegalArgumentException("assignment length " + assignment.length);
        while (f > TRUE) f = assignment[level(f)] ? his[f] : los[f];
        return f == TRUE;
    }

    /** Number of satisfying assignments over all {@code numVars} variables. */
    public BigInteger satCount(int f) {
        node(f);
        Map<Integer, BigInteger> memo = new HashMap<>();
        return satRec(f, memo).shiftLeft(topLevel(f));
    }

    private int topLevel(int f) {
        return f <= TRUE ? numVars : level(f);
    }

    /** Assignments of the variables from f's own level to the last one. */
    private BigInteger satRec(int f, Map<Integer, BigInteger> memo) {
        if (f == FALSE) return BigInteger.ZERO;
        if (f == TRUE) return BigInteger.ONE;
        BigInteger m = memo.get(f);
        if (m != null) return m;
        BigInteger lo = satRec(los[f], memo).shiftLeft(topLevel(los[f]) - level(f) - 1);
        BigInteger hi = satRec(his[f], memo).shiftLeft(topLevel(his[f]) - level(f) - 1);
        BigInteger r = lo.add(hi);
        memo.put(f, r);
        return r;
    }

    /** One satisfying assignment (0, 1, or 2 = don't care) or {@code null} for FALSE. */
    public byte[] anySat(int f) {
        node(f);
        if (f == FALSE) return null;
        byte[] out = new byte[numVars];
        Arrays.fill(out, (byte) 2);
        while (f > TRUE) {
            if (los[f] != FALSE) {
                out[level(f)] = 0;
                f = los[f];
            } else {
                out[level(f)] = 1;
                f = his[f];
            }
        }
        return out;
    }

    /** Paths to TRUE as strings of 0, 1 and '-' (variable not tested), low branch first. */
    public List<String> cubes(int f) {
        node(f);
        List<String> out = new ArrayList<>();
        char[] cube = new char[numVars];
        Arrays.fill(cube, '-');
        cubesRec(f, cube, out);
        return out;
    }

    private void cubesRec(int f, char[] cube, List<String> out) {
        if (f == FALSE) return;
        if (f == TRUE) {
            out.add(new String(cube));
            return;
        }
        int v = level(f);
        cube[v] = '0';
        cubesRec(los[f], cube, out);
        cube[v] = '1';
        cubesRec(his[f], cube, out);
        cube[v] = '-';
    }

    /** Variables the function genuinely depends on, ascending. */
    public List<Integer> support(int f) {
        node(f);
        BitSet seen = new BitSet(count);
        BitSet used = new BitSet(numVars);
        List<Integer> stack = new ArrayList<>();
        stack.add(f);
        while (!stack.isEmpty()) {
            int n = stack.remove(stack.size() - 1);
            if (n <= TRUE || seen.get(n)) continue;
            seen.set(n);
            used.set(level(n));
            stack.add(los[n]);
            stack.add(his[n]);
        }
        List<Integer> out = new ArrayList<>();
        for (int i = used.nextSetBit(0); i >= 0; i = used.nextSetBit(i + 1)) out.add(i);
        return out;
    }

    /** Number of reachable non-terminal nodes. */
    public int size(int f) {
        node(f);
        BitSet seen = new BitSet(count);
        List<Integer> stack = new ArrayList<>();
        stack.add(f);
        int n = 0;
        while (!stack.isEmpty()) {
            int x = stack.remove(stack.size() - 1);
            if (x <= TRUE || seen.get(x)) continue;
            seen.set(x);
            n++;
            stack.add(los[x]);
            stack.add(his[x]);
        }
        return n;
    }

    // ---- invariant checker -----------------------------------------------

    /**
     * Verifies the structural invariants of every node. Throws {@link IllegalStateException} whose
     * message starts with {@code range}, {@code redundant}, {@code order} or {@code duplicate}.
     */
    public void check() {
        Set<Key> seen = new HashSet<>();
        for (int id = 2; id < count; id++) {
            int lo = los[id], hi = his[id], v = vars[id];
            if (v < 0 || v >= numVars || lo < 0 || lo >= count || hi < 0 || hi >= count)
                throw new IllegalStateException("range: node " + id + " = (" + v + "," + lo + "," + hi + ")");
            if (lo == hi) throw new IllegalStateException("redundant: node " + id);
            if (vars[lo] <= v || vars[hi] <= v) throw new IllegalStateException("order: node " + id);
            if (!seen.add(new Key(v, lo, hi))) throw new IllegalStateException("duplicate: node " + id);
        }
    }

    // ---- test hooks (package-private) --------------------------------------

    int top() {
        return count - 1;
    }

    int varOf(int f) {
        return vars[f];
    }

    int loOf(int f) {
        return los[f];
    }

    int hiOf(int f) {
        return his[f];
    }

    void corruptForTest(int id, int var, int lo, int hi) {
        vars[id] = var;
        los[id] = lo;
        his[id] = hi;
    }
}
