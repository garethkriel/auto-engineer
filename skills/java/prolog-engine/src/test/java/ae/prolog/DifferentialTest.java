package ae.prolog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Compares the engine with plain Java oracles on seeded random inputs. */
class DifferentialTest {
    private static String list(List<? extends Number> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(xs.get(i));
        }
        return sb.append(']').toString();
    }

    private static Term parse(String s) {
        return Parser.parseTerm(s);
    }

    // ---- arithmetic: random expression trees evaluated by Java with Math.*Exact ----
    /** Java-side expression: text for Prolog plus the value, or null when evaluation must fail. */
    private record Ex(String text, Long value, boolean divZero, boolean overflow) {}

    private Ex genEx(Random r, int depth) {
        if (depth == 0 || r.nextInt(5) == 0) {
            long v = switch (r.nextInt(6)) {
                case 0 -> Long.MAX_VALUE - r.nextInt(2);
                case 1 -> Long.MIN_VALUE + r.nextInt(2);
                case 2 -> r.nextInt(5) - 2;
                default -> r.nextInt(2001) - 1000;
            };
            String t = v < 0 ? "(" + v + ")" : Long.toString(v);
            return new Ex(t, v, false, false);
        }
        String[] ops = {"+", "-", "*", "//", "mod", "rem", "min", "max", "neg", "abs"};
        String op = ops[r.nextInt(ops.length)];
        Ex a = genEx(r, depth - 1);
        if (op.equals("neg") || op.equals("abs")) {
            String t = op.equals("neg") ? "-(" + a.text + ")" : "abs(" + a.text + ")";
            if (a.value == null) return new Ex(t, null, a.divZero, a.overflow);
            try {
                long v = op.equals("neg") ? Math.negateExact(a.value) : (a.value < 0 ? Math.negateExact(a.value) : a.value);
                return new Ex(t, v, false, false);
            } catch (ArithmeticException ex) {
                return new Ex(t, null, false, true);
            }
        }
        Ex b = genEx(r, depth - 1);
        boolean infix = !(op.equals("min") || op.equals("max"));
        String t = infix ? "(" + a.text + " " + op + " " + b.text + ")" : op + "(" + a.text + "," + b.text + ")";
        if (a.value == null || b.value == null) {
            return new Ex(t, null, a.divZero || b.divZero, a.overflow || b.overflow);
        }
        long x = a.value, y = b.value;
        try {
            long v;
            switch (op) {
                case "+": v = Math.addExact(x, y); break;
                case "-": v = Math.subtractExact(x, y); break;
                case "*": v = Math.multiplyExact(x, y); break;
                case "min": v = Math.min(x, y); break;
                case "max": v = Math.max(x, y); break;
                case "//":
                    if (y == 0) return new Ex(t, null, true, false);
                    if (x == Long.MIN_VALUE && y == -1) return new Ex(t, null, false, true);
                    v = x / y;
                    break;
                case "mod":
                    if (y == 0) return new Ex(t, null, true, false);
                    v = Math.floorMod(x, y);
                    break;
                default:
                    if (y == 0) return new Ex(t, null, true, false);
                    v = x % y;
                    break;
            }
            return new Ex(t, v, false, false);
        } catch (ArithmeticException ex) {
            return new Ex(t, null, false, true);
        }
    }

    @Test
    void arithmeticAgreesWithJavaExactMath() {
        Engine e = new Engine();
        Random r = new Random(1124);
        int values = 0, errors = 0;
        for (int i = 0; i < 1500; i++) {
            Ex ex = genEx(r, 4);
            String q = "X is " + ex.text;
            if (ex.value != null) {
                Solution s = e.first(q).orElseThrow(() -> new AssertionError("no solution: " + q));
                assertEquals(new Int(ex.value), s.get("X"), q);
                values++;
            } else {
                PrologError err = assertThrows(PrologError.class, () -> e.first(q), q);
                assertEquals(PrologError.Kind.EVALUATION, err.kind(), q);
                errors++;
            }
        }
        // the generator must exercise both outcomes, otherwise the test checks nothing
        assertTrue(values > 300, "values=" + values);
        assertTrue(errors > 100, "errors=" + errors);
    }

    // ---- lists ----
    @Test
    void appendEnumeratesEverySplit() {
        Engine e = new Engine();
        for (int n = 0; n <= 6; n++) {
            List<Integer> xs = new ArrayList<>();
            for (int i = 0; i < n; i++) xs.add(i * 3 + 1);
            List<Solution> sols = e.all("append(A,B," + list(xs) + ")");
            assertEquals(n + 1, sols.size());
            for (int k = 0; k <= n; k++) {
                assertEquals(parse(list(xs.subList(0, k))), sols.get(k).get("A"), "n=" + n + " k=" + k);
                assertEquals(parse(list(xs.subList(k, n))), sols.get(k).get("B"), "n=" + n + " k=" + k);
            }
        }
    }

    @Test
    void permutationCountsAndContents() {
        Engine e = new Engine();
        long[] fact = {1, 1, 2, 6, 24, 120, 720};
        for (int n = 0; n <= 6; n++) {
            List<Integer> xs = new ArrayList<>();
            for (int i = 1; i <= n; i++) xs.add(i);
            List<Solution> sols = e.all("permutation(" + list(xs) + ",P)");
            assertEquals(fact[n], sols.size(), "n=" + n);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Solution s : sols) {
                List<Term> items = Terms.toJavaList(s.get("P"));
                List<Integer> sorted = new ArrayList<>();
                for (Term t : items) sorted.add((int) ((Int) t).value());
                assertTrue(seen.add(sorted.toString()), "duplicate permutation " + sorted);
                Collections.sort(sorted);
                assertEquals(xs, sorted);
            }
        }
    }

    @Test
    void userDefinedQuicksortAgreesWithJavaSort() {
        Engine e = new Engine();
        e.consult("qs([],[]). qs([P|Xs],S) :- part(Xs,P,L,G), qs(L,SL), qs(G,SG), append(SL,[P|SG],S).\n"
                + "part([],_,[],[]). part([X|Xs],P,[X|L],G) :- X =< P, !, part(Xs,P,L,G).\n"
                + "part([X|Xs],P,L,[X|G]) :- part(Xs,P,L,G).\n");
        Random r = new Random(99);
        for (int i = 0; i < 200; i++) {
            List<Integer> xs = new ArrayList<>();
            int n = r.nextInt(25);
            for (int j = 0; j < n; j++) xs.add(r.nextInt(15) - 5);
            List<Integer> expected = new ArrayList<>(xs);
            Collections.sort(expected);
            Solution s = e.first("qs(" + list(xs) + ",S)").orElseThrow();
            assertEquals(parse(list(expected)), s.get("S"), list(xs));
            // builtins agree with Java too: msort keeps duplicates, sort removes them
            assertEquals(parse(list(expected)), e.first("msort(" + list(xs) + ",S)").orElseThrow().get("S"));
            List<Integer> dedup = new ArrayList<>(new java.util.TreeSet<>(xs));
            assertEquals(parse(list(dedup)), e.first("sort(" + list(xs) + ",S)").orElseThrow().get("S"));
        }
    }

    @Test
    void reverseLengthAndSumAgreeWithJava() {
        Engine e = new Engine();
        Random r = new Random(7);
        for (int i = 0; i < 100; i++) {
            List<Integer> xs = new ArrayList<>();
            int n = r.nextInt(30);
            long sum = 0;
            for (int j = 0; j < n; j++) {
                int v = r.nextInt(100);
                xs.add(v);
                sum += v;
            }
            Solution s = e.first("reverse(" + list(xs) + ",R), length(R,N), sum_list(R,S)").orElseThrow();
            List<Integer> rev = new ArrayList<>(xs);
            Collections.reverse(rev);
            assertEquals(parse(list(rev)), s.get("R"));
            assertEquals(new Int(n), s.get("N"));
            assertEquals(new Int(sum), s.get("S"));
        }
    }

    @Test
    void findallMatchesStreamFilter() {
        Engine e = new Engine();
        e.consult("num(1). num(2). num(3). num(4). num(5). num(6). num(7). num(8). num(9). num(10).");
        Solution s = e.first("findall(X-Y, (num(X), num(Y), X < Y, 0 =:= (X+Y) mod 5), L)").orElseThrow();
        List<String> expected = new ArrayList<>();
        for (int x = 1; x <= 10; x++) for (int y = 1; y <= 10; y++) if (x < y && (x + y) % 5 == 0) expected.add(x + "-" + y);
        assertEquals(parse("[" + String.join(",", expected) + "]"), s.get("L"));
        assertEquals(9, expected.size(), "fixture: pairs summing to 5, 10 or 15");
    }
}
