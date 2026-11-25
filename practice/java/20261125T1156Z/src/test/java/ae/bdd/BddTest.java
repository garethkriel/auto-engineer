package ae.bdd;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BddTest {

    // ---- literal checks -------------------------------------------------

    @Test
    void terminalsAndVariables() {
        Bdd b = new Bdd(3, 1000);
        assertEquals(0, Bdd.FALSE);
        assertEquals(1, Bdd.TRUE);
        int x1 = b.var(1);
        assertEquals(x1, b.var(1)); // canonical: same node
        assertNotEquals(x1, b.nvar(1));
        assertEquals(Bdd.FALSE, b.and(x1, b.nvar(1)));
        assertEquals(Bdd.TRUE, b.or(x1, b.nvar(1)));
        assertEquals(b.nvar(1), b.not(x1));
        assertEquals(x1, b.not(b.not(x1)));
        assertTrue(b.eval(x1, new boolean[] {false, true, false}));
        assertFalse(b.eval(x1, new boolean[] {true, false, true}));
        assertEquals(1, b.size(x1));
        assertEquals(0, b.size(Bdd.TRUE));
        b.check();
    }

    @Test
    void deMorganAndAbsorptionAreIdentities() {
        Bdd b = new Bdd(4, 10000);
        int a = b.var(0), c = b.var(1), d = b.var(2);
        assertEquals(b.not(b.and(a, c)), b.or(b.not(a), b.not(c)));
        assertEquals(b.not(b.or(a, c)), b.and(b.not(a), b.not(c)));
        assertEquals(a, b.or(a, b.and(a, d)));
        assertEquals(b.xor(a, c), b.or(b.and(a, b.not(c)), b.and(b.not(a), c)));
        assertEquals(b.iff(a, c), b.not(b.xor(a, c)));
        assertEquals(b.imp(a, c), b.or(b.not(a), c));
        assertEquals(b.ite(a, c, d), b.or(b.and(a, c), b.and(b.not(a), d)));
        b.check();
    }

    @Test
    void parityHasTwoNMinusOneNodes() {
        for (int n = 1; n <= 10; n++) {
            Bdd b = new Bdd(n, 100000);
            int f = Bdd.FALSE;
            for (int i = 0; i < n; i++) f = b.xor(f, b.var(i));
            assertEquals(2 * n - 1, b.size(f), "n=" + n);
            assertEquals(BigInteger.TWO.pow(n - 1), b.satCount(f), "n=" + n);
        }
    }

    @Test
    void satCountLiterals() {
        Bdd b = new Bdd(3, 1000);
        assertEquals(BigInteger.valueOf(8), b.satCount(Bdd.TRUE));
        assertEquals(BigInteger.ZERO, b.satCount(Bdd.FALSE));
        assertEquals(BigInteger.valueOf(4), b.satCount(b.var(2)));
        // a | b over three variables: 8 - (a=0,b=0) * 2 values of c = 6
        assertEquals(BigInteger.valueOf(6), b.satCount(b.or(b.var(0), b.var(1))));
        // a & c skips variable 1 in the middle: 2 free values of b
        assertEquals(BigInteger.valueOf(2), b.satCount(b.and(b.var(0), b.var(2))));
    }

    @Test
    void variableOrderChangesSize() {
        Bdd good = new Bdd(6, 100000);
        int f = Bdd.FALSE;
        for (int i = 0; i < 3; i++) f = good.or(f, good.and(good.var(2 * i), good.var(2 * i + 1)));
        assertEquals(6, good.size(f));
        Bdd bad = new Bdd(6, 100000);
        int g = Bdd.FALSE;
        for (int i = 0; i < 3; i++) g = bad.or(g, bad.and(bad.var(i), bad.var(i + 3)));
        // x levels hold 1, 2, 4 nodes. At y0 only the 4 subfunctions OR(y_i : x_i=1) with x0=1 depend on y0;
        // then 2 depend on y1 ({y1}, {y1,y2}) and 1 on y2: 7 + 4 + 2 + 1 = 14 = 2^(n+1) - 2
        assertEquals(14, bad.size(g));
        assertEquals(good.satCount(f), bad.satCount(g));
    }

    @Test
    void anySatAndCubes() {
        Bdd b = new Bdd(3, 1000);
        assertNull(b.anySat(Bdd.FALSE));
        assertArrayEquals(new byte[] {2, 2, 2}, b.anySat(Bdd.TRUE));
        int f = b.and(b.var(0), b.nvar(2));
        assertArrayEquals(new byte[] {1, 2, 0}, b.anySat(f));
        assertEquals(List.of("1-0"), b.cubes(f));
        int g = b.xor(b.var(0), b.var(1));
        assertEquals(List.of("01-", "10-"), b.cubes(g));
        assertEquals(List.of(), b.cubes(Bdd.FALSE));
        assertEquals(List.of("---"), b.cubes(Bdd.TRUE));
    }

    @Test
    void supportListsOnlyEssentialVariables() {
        Bdd b = new Bdd(5, 1000);
        int f = b.or(b.and(b.var(0), b.var(3)), b.and(b.var(0), b.nvar(3))); // = x0
        assertEquals(b.var(0), f);
        assertEquals(List.of(0), b.support(f));
        assertEquals(List.of(1, 4), b.support(b.xor(b.var(1), b.var(4))));
        assertEquals(List.of(), b.support(Bdd.TRUE));
    }

    @Test
    void quantifiersLiterals() {
        Bdd b = new Bdd(3, 1000);
        int f = b.and(b.var(0), b.var(1));
        assertEquals(b.var(1), b.exists(f, 0));
        assertEquals(Bdd.FALSE, b.forall(f, 0));
        assertEquals(Bdd.TRUE, b.exists(f, 0, 1));
        assertEquals(f, b.exists(f, 2)); // variable not in support
        assertEquals(b.var(0), b.restrict(b.or(b.var(0), b.var(1)), 1, false));
        assertEquals(Bdd.TRUE, b.restrict(b.or(b.var(0), b.var(1)), 1, true));
        // compose x0 := (x1 ^ x2) into x0 & x1
        int composed = b.compose(f, 0, b.xor(b.var(1), b.var(2)));
        assertEquals(b.and(b.xor(b.var(1), b.var(2)), b.var(1)), composed);
        assertEquals(b.and(b.var(1), b.nvar(2)), composed);
        assertEquals(b.exists(b.and(f, b.var(2)), 0), b.andExists(f, b.var(2), 0));
    }

    @Test
    void nodeLimitIsEnforcedAndLeavesManagerUsable() {
        Bdd b = new Bdd(8, 6); // terminals + 4 non-terminals
        int f = b.var(0);
        int g = b.var(1);
        int h = b.var(2);
        int k = b.var(3);
        assertThrows(IllegalStateException.class, () -> b.var(4));
        int before = b.nodeCount();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.xor(f, b.xor(g, b.xor(h, k))));
        assertTrue(e.getMessage().startsWith("node limit"), e.getMessage());
        assertTrue(b.nodeCount() <= 6);
        assertTrue(b.nodeCount() >= before);
        b.check();
        assertEquals(f, b.and(f, Bdd.TRUE)); // operations needing no new node still work at the limit
        assertEquals(f, b.or(f, f));
    }

    @Test
    void argumentValidation() {
        Bdd b = new Bdd(3, 100);
        assertThrows(IllegalArgumentException.class, () -> b.var(3));
        assertThrows(IllegalArgumentException.class, () -> b.var(-1));
        assertThrows(IllegalArgumentException.class, () -> b.and(0, 99));
        assertThrows(IllegalArgumentException.class, () -> b.eval(Bdd.TRUE, new boolean[2]));
        assertThrows(IllegalArgumentException.class, () -> b.exists(Bdd.TRUE, 7));
        assertThrows(IllegalArgumentException.class, () -> new Bdd(-1, 10));
    }

    // ---- independent oracle: truth tables over a random expression tree ---

    /** Tiny expression tree evaluated directly in Java; the oracle for everything below. */
    sealed interface E permits V, N, B {}
    record V(int i) implements E {}
    record N(E e) implements E {}
    record B(char op, E l, E r) implements E {}

    static E randomExpr(Random r, int vars, int depth) {
        if (depth == 0 || r.nextInt(5) == 0) return new V(r.nextInt(vars));
        if (r.nextInt(6) == 0) return new N(randomExpr(r, vars, depth - 1));
        char op = "&|^>=".charAt(r.nextInt(5));
        return new B(op, randomExpr(r, vars, depth - 1), randomExpr(r, vars, depth - 1));
    }

    static boolean evalE(E e, boolean[] a) {
        if (e instanceof V v) return a[v.i()];
        if (e instanceof N n) return !evalE(n.e(), a);
        B b = (B) e;
        boolean l = evalE(b.l(), a), rr = evalE(b.r(), a);
        return switch (b.op()) {
            case '&' -> l && rr;
            case '|' -> l || rr;
            case '^' -> l != rr;
            case '>' -> !l || rr;
            default -> l == rr;
        };
    }

    static int build(Bdd m, E e) {
        if (e instanceof V v) return m.var(v.i());
        if (e instanceof N n) return m.not(build(m, n.e()));
        B b = (B) e;
        int l = build(m, b.l()), r = build(m, b.r());
        return switch (b.op()) {
            case '&' -> m.and(l, r);
            case '|' -> m.or(l, r);
            case '^' -> m.xor(l, r);
            case '>' -> m.imp(l, r);
            default -> m.iff(l, r);
        };
    }

    static boolean[] assignment(int bits, int n) {
        boolean[] a = new boolean[n];
        for (int i = 0; i < n; i++) a[i] = (bits >> i & 1) == 1;
        return a;
    }

    @Test
    void randomExpressionsAgreeWithTruthTables() {
        Random rnd = new Random(20261125);
        final int n = 6;
        int nonTrivial = 0;
        for (int round = 0; round < 300; round++) {
            Bdd m = new Bdd(n, 200000);
            E e = randomExpr(rnd, n, 6);
            int f = build(m, e);
            int ones = 0;
            for (int bits = 0; bits < 1 << n; bits++) {
                boolean[] a = assignment(bits, n);
                boolean expect = evalE(e, a);
                assertEquals(expect, m.eval(f, a), "round " + round + " bits " + bits);
                if (expect) ones++;
            }
            assertEquals(BigInteger.valueOf(ones), m.satCount(f), "round " + round);
            if (ones > 0 && ones < 1 << n) nonTrivial++;
            // cubes expand to exactly the satisfying set
            boolean[] covered = new boolean[1 << n];
            for (String c : m.cubes(f)) expandCube(c, 0, 0, covered);
            for (int bits = 0; bits < 1 << n; bits++)
                assertEquals(evalE(e, assignment(bits, n)), covered[bits], "cube cover round " + round);
            byte[] sat = m.anySat(f);
            if (ones == 0) assertNull(sat);
            else {
                boolean[] a = new boolean[n];
                for (int i = 0; i < n; i++) a[i] = sat[i] == 1; // don't-care (2) -> false, must still satisfy
                assertTrue(evalE(e, a), "anySat round " + round);
            }
            m.check();
        }
        assertTrue(nonTrivial > 100, "generator too degenerate: " + nonTrivial);
    }

    private static void expandCube(String c, int idx, int bits, boolean[] covered) {
        if (idx == c.length()) {
            covered[bits] = true;
            return;
        }
        char ch = c.charAt(idx);
        if (ch != '1') expandCube(c, idx + 1, bits, covered);
        if (ch != '0') expandCube(c, idx + 1, bits | 1 << idx, covered);
    }

    @Test
    void quantifiersRestrictComposeAgreeWithTruthTables() {
        Random rnd = new Random(77);
        final int n = 5;
        for (int round = 0; round < 200; round++) {
            Bdd m = new Bdd(n, 200000);
            E e = randomExpr(rnd, n, 5);
            E ge = randomExpr(rnd, n, 3);
            int f = build(m, e), g = build(m, ge);
            int v = rnd.nextInt(n), w = rnd.nextInt(n);
            int ex = m.exists(f, v, w), fa = m.forall(f, v, w);
            int r0 = m.restrict(f, v, false), r1 = m.restrict(f, v, true);
            int comp = m.compose(f, v, g);
            int ae = m.andExists(f, g, v, w);
            for (int bits = 0; bits < 1 << n; bits++) {
                boolean[] a = assignment(bits, n);
                boolean anyV = false, allV = true, anyAnd = false;
                for (int choice = 0; choice < 4; choice++) {
                    boolean[] c = a.clone();
                    c[v] = (choice & 1) == 1;
                    c[w] = (choice & 2) == 2;
                    boolean val = evalE(e, c);
                    anyV |= val;
                    allV &= val;
                    anyAnd |= val && evalE(ge, c);
                }
                assertEquals(anyV, m.eval(ex, a), "exists r" + round);
                assertEquals(allV, m.eval(fa, a), "forall r" + round);
                assertEquals(anyAnd, m.eval(ae, a), "andExists r" + round);
                boolean[] c0 = a.clone(), c1 = a.clone();
                c0[v] = false;
                c1[v] = true;
                assertEquals(evalE(e, c0), m.eval(r0, a), "restrict0 r" + round);
                assertEquals(evalE(e, c1), m.eval(r1, a), "restrict1 r" + round);
                boolean[] cc = a.clone();
                cc[v] = evalE(ge, a);
                assertEquals(evalE(e, cc), m.eval(comp, a), "compose r" + round);
            }
            m.check();
        }
    }

    /**
     * Oracle for node counts that does not look at the BDD: level i holds one node per
     * distinct subfunction (truth table over variables i..n-1, after fixing 0..i-1)
     * that genuinely depends on variable i.
     */
    static int oracleSize(boolean[] table, int n) {
        int total = 0;
        for (int level = 0; level < n; level++) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            int rest = n - level;
            for (int prefix = 0; prefix < 1 << level; prefix++) {
                StringBuilder sb = new StringBuilder();
                boolean depends = false;
                for (int tail = 0; tail < 1 << rest; tail++) {
                    int idx = prefix | tail << level;
                    sb.append(table[idx] ? '1' : '0');
                    int flipped = prefix | (tail ^ 1) << level;
                    if (table[idx] != table[flipped]) depends = true;
                }
                if (depends) seen.add(sb.toString());
            }
            total += seen.size();
        }
        return total;
    }

    @Test
    void nodeCountsMatchSubfunctionOracle() {
        Random rnd = new Random(5);
        final int n = 7;
        for (int round = 0; round < 150; round++) {
            Bdd m = new Bdd(n, 500000);
            E e = randomExpr(rnd, n, 6);
            int f = build(m, e);
            boolean[] table = new boolean[1 << n];
            for (int bits = 0; bits < 1 << n; bits++) table[bits] = evalE(e, assignment(bits, n));
            assertEquals(oracleSize(table, n), m.size(f), "round " + round);
        }
    }

    // ---- applications ---------------------------------------------------

    static BigInteger queens(int n) {
        Bdd m = new Bdd(n * n, 3_000_000);
        int all = Bdd.TRUE;
        for (int r = 0; r < n; r++) {
            int rowAny = Bdd.FALSE;
            for (int c = 0; c < n; c++) rowAny = m.or(rowAny, m.var(r * n + c));
            all = m.and(all, rowAny);
            for (int c = 0; c < n; c++) {
                int q = m.var(r * n + c);
                for (int r2 = 0; r2 < n; r2++)
                    for (int c2 = 0; c2 < n; c2++) {
                        if (r2 * n + c2 <= r * n + c) continue;
                        boolean attack = r == r2 || c == c2 || Math.abs(r - r2) == Math.abs(c - c2);
                        if (attack) all = m.and(all, m.not(m.and(q, m.var(r2 * n + c2))));
                    }
            }
        }
        m.check();
        return m.satCount(all);
    }

    @Test
    void nQueensSolutionCounts() {
        assertEquals(BigInteger.valueOf(10), queens(5));
        assertEquals(BigInteger.valueOf(4), queens(6));
        assertEquals(BigInteger.valueOf(40), queens(7));
        assertEquals(BigInteger.valueOf(92), queens(8));
    }

    /** Ripple-carry sum bits for a + b, interleaved variable order a0 b0 a1 b1 ... */
    static int[] rippleAdder(Bdd m, int bits, boolean swapOperands) {
        int[] sum = new int[bits + 1];
        int carry = Bdd.FALSE;
        for (int i = 0; i < bits; i++) {
            int x = m.var(2 * i), y = m.var(2 * i + 1);
            if (swapOperands) {
                int t = x;
                x = y;
                y = t;
            }
            sum[i] = m.xor(m.xor(x, y), carry);
            carry = m.or(m.and(x, y), m.and(carry, m.xor(x, y)));
        }
        sum[bits] = carry;
        return sum;
    }

    /** Different formulation: majority-based carry and generate/propagate sums. */
    static int[] gpAdder(Bdd m, int bits) {
        int[] sum = new int[bits + 1];
        int carry = Bdd.FALSE;
        for (int i = 0; i < bits; i++) {
            int x = m.var(2 * i), y = m.var(2 * i + 1);
            int g = m.and(x, y), p = m.or(x, y);
            sum[i] = m.iff(m.iff(x, y), carry);
            carry = m.or(g, m.and(p, carry));
        }
        sum[bits] = carry;
        return sum;
    }

    @Test
    void addersAreEquivalentAndMatchIntegerAddition() {
        final int bits = 4;
        Bdd m = new Bdd(2 * bits, 200000);
        int[] r1 = rippleAdder(m, bits, false);
        int[] r2 = rippleAdder(m, bits, true);
        int[] r3 = gpAdder(m, bits);
        // sum bit i of gpAdder is xnor(xnor(x,y),carry) = xor(xor(x,y),carry): identical id
        for (int i = 0; i <= bits; i++) {
            assertEquals(r1[i], r2[i], "commutativity bit " + i);
            assertEquals(r1[i], r3[i], "formulation bit " + i);
        }
        for (int a = 0; a < 16; a++)
            for (int c = 0; c < 16; c++) {
                boolean[] asg = new boolean[8];
                for (int i = 0; i < bits; i++) {
                    asg[2 * i] = (a >> i & 1) == 1;
                    asg[2 * i + 1] = (c >> i & 1) == 1;
                }
                int got = 0;
                for (int i = 0; i <= bits; i++) if (m.eval(r1[i], asg)) got |= 1 << i;
                assertEquals(a + c, got, a + "+" + c);
            }
        m.check();
    }

    @Test
    void brokenAdderYieldsCounterexample() {
        final int bits = 4;
        Bdd m = new Bdd(2 * bits, 200000);
        int[] good = rippleAdder(m, bits, false);
        int[] bad = good.clone();
        // fault: carry out of bit 2 forgotten, i.e. sum[3] ignores carry-in from bit 2
        int x3 = m.var(6), y3 = m.var(7);
        bad[3] = m.xor(x3, y3);
        int diff = m.xor(good[3], bad[3]);
        assertNotEquals(Bdd.FALSE, diff);
        byte[] cex = m.anySat(diff);
        boolean[] asg = new boolean[8];
        for (int i = 0; i < 8; i++) asg[i] = cex[i] == 1;
        assertNotEquals(m.eval(good[3], asg), m.eval(bad[3], asg));
        // number of differing inputs: carry into bit 3 is 1 for 2^? inputs; recount by brute force
        int expect = 0;
        for (int bitsAsg = 0; bitsAsg < 256; bitsAsg++) {
            boolean[] a = assignment(bitsAsg, 8);
            if (m.eval(good[3], a) != m.eval(bad[3], a)) expect++;
        }
        assertEquals(BigInteger.valueOf(expect), m.satCount(diff));
        // carry into bit 3 from the low 3 bits: pairs (a,b) in 0..7 with a+b >= 8 is 28; times 4 for (x3,y3)
        assertEquals(BigInteger.valueOf(28 * 4), m.satCount(diff));
    }

    // ---- checker -------------------------------------------------------

    @Test
    void checkerRejectsEachKindOfCorruption() {
        Bdd m = new Bdd(3, 100);
        Bdd r = fresh();
        r.corruptForTest(r.top(), r.varOf(r.top()), 1, 1);
        assertTrue(msg(r).startsWith("redundant"), msg(r));

        Bdd o = fresh();
        int top = o.top();
        o.corruptForTest(top, 2, o.loOf(top), o.hiOf(top)); // parent var 2 above child var 1
        assertTrue(msg(o).startsWith("order"), msg(o));

        // child at the SAME level as its parent is also an order violation (lo side, then hi side)
        Bdd same = fresh();
        same.corruptForTest(5, 2, 0, 4); // node 5 now var 2 with hi child x2 (var 2)
        assertTrue(msg(same).startsWith("order"), msg(same));
        Bdd sameHi = fresh();
        sameHi.corruptForTest(6, 1, 0, 5); // top var 1 with hi child (x1&x2) at var 1
        assertTrue(msg(sameHi).startsWith("order"), msg(sameHi));
        Bdd sameLo = fresh();
        sameLo.corruptForTest(5, 2, 4, 1); // node 5 var 2, lo child x2 (var 2); hi is a terminal, so only lo offends
        assertTrue(msg(sameLo).startsWith("order"), msg(sameLo));

        Bdd d = fresh();
        d.corruptForTest(3, 2, 0, 1); // node 3 (x1) becomes a copy of node 4 (x2)
        assertTrue(msg(d).startsWith("duplicate"), msg(d));

        Bdd g = fresh();
        g.corruptForTest(g.top(), g.varOf(g.top()), 0, 99);
        assertTrue(msg(g).startsWith("range"), msg(g));
    }

    /** x0 & x1 & x2 style chain: nodes 2 (x2), 3 (x1&x2), 4 (x0&x1&x2). */
    private static Bdd fresh() {
        Bdd m = new Bdd(3, 100);
        m.and(m.var(0), m.and(m.var(1), m.var(2))); // ids: 2=x0 3=x1 4=x2 5=(x1&x2) 6=(x0&x1&x2)
        m.check();
        return m;
    }

    private static String msg(Bdd b) {
        return assertThrows(IllegalStateException.class, b::check).getMessage();
    }

    // ---- parser --------------------------------------------------------

    private static int parse(Bdd m, String s, List<String> names) {
        return Expr.parse(m, s, names);
    }

    @Test
    void parserPrecedenceAndAssociativity() {
        Bdd m = new Bdd(4, 10000);
        List<String> names = new ArrayList<>(List.of("a", "b", "c", "d"));
        int a = m.var(0), b = m.var(1), c = m.var(2), d = m.var(3);
        assertEquals(m.or(a, m.and(b, c)), parse(m, "a | b & c", names));
        assertEquals(m.or(m.and(a, b), c), parse(m, "a & b | c", names));
        assertEquals(m.or(a, m.xor(b, m.and(c, d))), parse(m, "a | b ^ c & d", names));
        assertEquals(m.xor(m.or(a, b), c), parse(m, "(a | b) ^ c", names));
        assertEquals(m.imp(a, m.imp(b, c)), parse(m, "a -> b -> c", names)); // right assoc
        assertNotEquals(m.imp(m.imp(a, b), c), parse(m, "a -> b -> c", names));
        assertEquals(m.iff(m.imp(a, b), m.or(m.not(a), b)), parse(m, "(a -> b) <-> !a | b", names));
        assertEquals(Bdd.TRUE, parse(m, "(a -> b) <-> !a | b", names));
        assertEquals(m.and(m.not(a), b), parse(m, "!a & b", names));
        assertEquals(m.not(m.and(a, b)), parse(m, "!(a & b)", names));
        assertEquals(m.not(a), parse(m, "!!!a", names));
        assertEquals(Bdd.FALSE, parse(m, "a & false", names));
        assertEquals(a, parse(m, "a | 0", names));
        assertEquals(Bdd.TRUE, parse(m, "1 | a", names));
        assertEquals(Bdd.TRUE, parse(m, "a | !a", names));
    }

    @Test
    void parserDeclaresNamesInOrderOfFirstAppearance() {
        Bdd m = new Bdd(3, 1000);
        List<String> names = new ArrayList<>();
        int f = parse(m, "q & (p | r_1)", names);
        assertEquals(List.of("q", "p", "r_1"), names);
        assertEquals(m.and(m.var(0), m.or(m.var(1), m.var(2))), f);
        assertEquals(m.var(1), parse(m, "p", names)); // reuse, no new name
        assertEquals(3, names.size());
    }

    @Test
    void parserReportsColumns() {
        Bdd m = new Bdd(2, 1000);
        List<String> names = new ArrayList<>();
        assertEquals("col 5: unexpected '&'", err(m, "a & & b", names));
        assertEquals("col 7: expected ')'", err(m, "(a & b", names));
        assertEquals("col 1: unexpected end of input", err(m, "", names));
        assertEquals("col 4: unexpected end of input", err(m, "a |", names));
        assertEquals("col 3: unexpected character '#'", err(m, "a #b", names));
        assertEquals("col 3: unexpected ')'", err(m, "a )", names));
        assertEquals("col 1: unexpected ')'", err(m, ")", names));
        assertEquals("col 3: unexpected '-'", err(m, "a - b", names));
        assertEquals("col 3: unexpected '<'", err(m, "a <- b", names));
        // two names already declared (a, b): a third is refused with its column
        assertEquals("col 5: too many variables ('zz')", err(m, "a & zz", new ArrayList<>(List.of("a", "b"))));
        // a failed parse leaves the name list as it found it
        List<String> kept = new ArrayList<>(List.of("k"));
        err(m, "a & & b", kept);
        assertEquals(List.of("k"), kept);
        // control: the same text parses when a slot is free
        assertEquals(m.and(m.var(0), m.var(1)), parse(m, "a & zz", new ArrayList<>(List.of("a"))));
    }

    private static String err(Bdd m, String s, List<String> names) {
        return assertThrows(IllegalArgumentException.class, () -> Expr.parse(m, s, names)).getMessage();
    }

    @Test
    void parserHandlesLongChainsWithoutOverflowForReasonableDepth() {
        Bdd m = new Bdd(30, 100000);
        StringBuilder sb = new StringBuilder("x0");
        for (int i = 1; i < 30; i++) sb.append(" ^ x").append(i);
        List<String> names = new ArrayList<>();
        int f = parse(m, sb.toString(), names);
        assertEquals(2 * 30 - 1, m.size(f));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append('(');
        deep.append("x0");
        for (int i = 0; i < 200; i++) deep.append(')');
        assertEquals(m.var(0), parse(m, deep.toString(), names));
    }

    @Test
    void parsedFormulaEquivalenceProofs() {
        Bdd m = new Bdd(3, 10000);
        List<String> n = new ArrayList<>();
        // distributivity, contraposition, Peirce's law, exportation
        assertEquals(parse(m, "a & (b | c)", n), parse(m, "a & b | a & c", n));
        assertEquals(parse(m, "a -> b", n), parse(m, "!b -> !a", n));
        assertEquals(Bdd.TRUE, parse(m, "((a -> b) -> a) -> a", n));
        assertEquals(parse(m, "a & b -> c", n), parse(m, "a -> (b -> c)", n));
        // a non-theorem is not TRUE and has a counterexample
        int bad = parse(m, "(a -> b) -> (b -> a)", n);
        assertNotEquals(Bdd.TRUE, bad);
        byte[] cex = m.anySat(m.not(bad));
        assertEquals(0, cex[0]); // a false, b true
        assertEquals(1, cex[1]);
    }
}
