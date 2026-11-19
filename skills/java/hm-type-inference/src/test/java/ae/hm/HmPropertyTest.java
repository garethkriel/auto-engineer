package ae.hm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised, oracle-independent checks. Terms are generated <em>from a target type</em>, so all of them are
 * well typed by construction. Oracles: (1) the principal type must be an instance of the target type, checked
 * by a tiny one-way matcher written here; (2) let-inlining preserves the principal type; (3) printing then
 * parsing gives the same term; (4) wrapping a non-Int term in {@code + 1} must be a MISMATCH;
 * (5) the substitution invariant holds after every inference.
 */
class HmPropertyTest {

    sealed interface T permits TInt, TBool, TFun, TPair {}
    record TInt() implements T {}
    record TBool() implements T {}
    record TFun(T from, T to) implements T {}
    record TPair(T l, T r) implements T {}

    /** Type-directed random term generator; every binder gets a globally unique name (no capture). */
    static final class Gen {
        final Random rnd;
        int fresh = 0;
        final Map<String, T> scope = new HashMap<>();
        final List<String> ids = new ArrayList<>();    // let id = \p -> p       (usable at every type)
        final List<String> consts = new ArrayList<>(); // let k = \a b -> a      (usable at every type)

        Gen(long seed) { rnd = new Random(seed); }

        T type(int depth) {
            int k = rnd.nextInt(depth <= 0 ? 2 : 4);
            return switch (k) {
                case 0 -> new TInt();
                case 1 -> new TBool();
                case 2 -> new TFun(type(depth - 1), type(depth - 1));
                default -> new TPair(type(depth - 1), type(depth - 1));
            };
        }

        String name(String prefix) { return prefix + (fresh++); }

        String fromScope(T t) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, T> e : scope.entrySet()) {
                if (e.getValue().equals(t)) {
                    names.add(e.getKey());
                }
            }
            if (names.isEmpty()) {
                return null;
            }
            names.sort(null); // HashMap order is not stable: sort before the seeded pick
            return names.get(rnd.nextInt(names.size()));
        }

        String leaf(T t) {
            return switch (t) {
                case TInt i -> String.valueOf(rnd.nextInt(10));
                case TBool b -> rnd.nextBoolean() ? "true" : "false";
                case TFun f -> {
                    String x = name("x");
                    scope.put(x, f.from());
                    String body = leaf(f.to());
                    scope.remove(x);
                    yield "(\\" + x + " -> " + body + ")";
                }
                case TPair p -> "(" + leaf(p.l()) + ", " + leaf(p.r()) + ")";
            };
        }

        String term(T t, int depth) {
            if (depth <= 0) {
                String v = fromScope(t);
                return v != null && rnd.nextBoolean() ? v : leaf(t);
            }
            int pick = rnd.nextInt(11);
            switch (pick) {
                case 0: {
                    String v = fromScope(t);
                    return v != null ? v : leaf(t);
                }
                case 1: { // monomorphic let
                    T t1 = type(1);
                    String v = name("x");
                    String e1 = term(t1, depth - 1);
                    scope.put(v, t1);
                    String e2 = term(t, depth - 1);
                    scope.remove(v);
                    return "(let " + v + " = " + e1 + " in " + e2 + ")";
                }
                case 2: { // let-bound identity, applied at the target type
                    String f = name("id");
                    ids.add(f);
                    String body = term(t, depth - 1);
                    ids.remove(f);
                    String p = name("p");
                    return "(let " + f + " = \\" + p + " -> " + p + " in " + body + ")";
                }
                case 3: { // let-bound constant function, applied at the target type
                    String k = name("k");
                    consts.add(k);
                    String body = term(t, depth - 1);
                    consts.remove(k);
                    String a = name("a");
                    String b = name("b");
                    return "(let " + k + " = \\" + a + " " + b + " -> " + a + " in " + body + ")";
                }
                case 4:
                    if (!ids.isEmpty()) {
                        return "(" + ids.get(rnd.nextInt(ids.size())) + " " + term(t, depth - 1) + ")";
                    }
                    break;
                case 5:
                    if (!consts.isEmpty()) {
                        String k = consts.get(rnd.nextInt(consts.size()));
                        String first = term(t, depth - 1);
                        String second = term(type(1), depth - 1);
                        return "(" + k + " " + first + " " + second + ")";
                    }
                    break;
                case 6: { // beta redex with a random argument type
                    T a = type(1);
                    String x = name("x");
                    String arg = term(a, depth - 1);
                    scope.put(x, a);
                    String body = term(t, depth - 1);
                    scope.remove(x);
                    return "((\\" + x + " -> " + body + ") " + arg + ")";
                }
                case 7: {
                    String c = term(new TBool(), depth - 1);
                    return "(if " + c + " then " + term(t, depth - 1) + " else " + term(t, depth - 1) + ")";
                }
                case 8: { // fst / snd
                    T other = type(1);
                    boolean first = rnd.nextBoolean();
                    T pair = first ? new TPair(t, other) : new TPair(other, t);
                    return (first ? "(fst " : "(snd ") + term(pair, depth - 1) + ")";
                }
                default:
                    break;
            }
            return structural(t, depth);
        }

        String structural(T t, int depth) {
            return switch (t) {
                case TInt i -> "(" + term(new TInt(), depth - 1) + " " + "+-*".charAt(rnd.nextInt(3)) + " "
                    + term(new TInt(), depth - 1) + ")";
                case TBool b -> rnd.nextBoolean()
                    ? "(" + term(new TInt(), depth - 1) + " == " + term(new TInt(), depth - 1) + ")"
                    : "(not " + term(new TBool(), depth - 1) + ")";
                case TFun f -> {
                    String x = name("x");
                    scope.put(x, f.from());
                    String body = term(f.to(), depth - 1);
                    scope.remove(x);
                    yield "(\\" + x + " -> " + body + ")";
                }
                case TPair p -> "(" + term(p.l(), depth - 1) + ", " + term(p.r(), depth - 1) + ")";
            };
        }
    }

    /** Is {@code pattern} (may contain variables, each bound consistently) a generalisation of ground {@code t}? */
    static boolean matches(Type pattern, T t, Map<Integer, T> binding) {
        if (pattern instanceof Type.TVar v) {
            T bound = binding.putIfAbsent(v.id(), t);
            return bound == null || bound.equals(t);
        }
        Type.TCon c = (Type.TCon) pattern;
        return switch (t) {
            case TInt i -> c.name().equals("Int");
            case TBool b -> c.name().equals("Bool");
            case TFun f -> c.name().equals("->") && matches(c.args().get(0), f.from(), binding)
                && matches(c.args().get(1), f.to(), binding);
            case TPair p -> c.name().equals("(,)") && matches(c.args().get(0), p.l(), binding)
                && matches(c.args().get(1), p.r(), binding);
        };
    }

    static String showT(T t) {
        return switch (t) {
            case TInt i -> "Int";
            case TBool b -> "Bool";
            case TFun f -> (f.from() instanceof TFun ? "(" + showT(f.from()) + ")" : showT(f.from())) + " -> " + showT(f.to());
            case TPair p -> "(" + showT(p.l()) + ", " + showT(p.r()) + ")";
        };
    }

    /** Replaces every non-recursive let by its body with the value substituted for the name. */
    static Expr inline(Expr e) {
        return switch (e) {
            case Expr.Var v -> v;
            case Expr.IntLit i -> i;
            case Expr.BoolLit b -> b;
            case Expr.Lam l -> new Expr.Lam(l.param(), inline(l.body()), l.pos());
            case Expr.App a -> new Expr.App(inline(a.fn()), inline(a.arg()), a.pos());
            case Expr.If f -> new Expr.If(inline(f.cond()), inline(f.thenBranch()), inline(f.elseBranch()), f.pos());
            case Expr.Pair p -> new Expr.Pair(inline(p.left()), inline(p.right()), p.pos());
            case Expr.Let l -> {
                assertFalse(l.rec(), "generator never produces let rec");
                yield substitute(inline(l.body()), l.name(), inline(l.value()));
            }
        };
    }

    static Expr substitute(Expr e, String name, Expr value) {
        return switch (e) {
            case Expr.Var v -> v.name().equals(name) ? value : v;
            case Expr.IntLit i -> i;
            case Expr.BoolLit b -> b;
            case Expr.Lam l -> l.param().equals(name) ? l
                : new Expr.Lam(l.param(), substitute(l.body(), name, value), l.pos());
            case Expr.App a -> new Expr.App(substitute(a.fn(), name, value), substitute(a.arg(), name, value), a.pos());
            case Expr.If f -> new Expr.If(substitute(f.cond(), name, value), substitute(f.thenBranch(), name, value),
                substitute(f.elseBranch(), name, value), f.pos());
            case Expr.Pair p -> new Expr.Pair(substitute(p.left(), name, value), substitute(p.right(), name, value), p.pos());
            case Expr.Let l -> new Expr.Let(l.name(), substitute(l.value(), name, value),
                l.name().equals(name) ? l.body() : substitute(l.body(), name, value), l.rec(), l.pos());
        };
    }

    static final int CASES = 600;

    @Test
    void principalTypeIsAnInstanceOfTheTargetType() {
        for (long seed = 1; seed <= CASES; seed++) {
            Gen g = new Gen(seed);
            T target = g.type(3);
            String src = g.term(target, 4);
            Type principal = Hm.principalType(src);
            assertTrue(matches(principal, target, new HashMap<>()),
                "seed " + seed + ": " + src + "\n  target " + showT(target) + "\n  inferred " + Hm.typeOf(src));
        }
    }

    @Test
    void groundTargetsWithoutPolymorphismAreInferredExactly() {
        // a term that is closed under its own structure: pairs of literals have exactly the generated type
        for (long seed = 1; seed <= 200; seed++) {
            Gen g = new Gen(seed);
            T target = g.type(3);
            String src = g.leaf(new TPair(target, new TInt()));
            // a leaf of function type ignores its argument, so its type is more general: assert exactly only without functions
            if (!containsFun(target)) {
                assertEquals("(" + showT(target) + ", Int)", Hm.typeOf(src), src);
            }
        }
    }

    static boolean containsFun(T t) {
        return switch (t) {
            case TInt i -> false;
            case TBool b -> false;
            case TFun f -> true;
            case TPair p -> containsFun(p.l()) || containsFun(p.r());
        };
    }

    @Test
    void inliningLetsPreservesThePrincipalType() {
        int withLets = 0;
        for (long seed = 1; seed <= CASES; seed++) {
            Gen g = new Gen(seed * 7919);
            String src = g.term(g.type(3), 5);
            Expr parsed = Parser.parse(src);
            Expr inlined = inline(parsed);
            if (src.contains("let ")) {
                withLets++;
            }
            assertEquals(Hm.typeOf(src), Hm.typeOf(inlined.show()),
                "seed " + seed + ": " + src + "\n  inlined " + inlined.show());
        }
        assertTrue(withLets > CASES / 4, "generator should produce lets often, got " + withLets);
    }

    @Test
    void printingAndReparsingGivesTheSameTerm() {
        for (long seed = 1; seed <= CASES; seed++) {
            Gen g = new Gen(seed * 31);
            String src = g.term(g.type(3), 5);
            String once = Parser.parse(src).show();
            String twice = Parser.parse(once).show();
            assertEquals(once, twice, "seed " + seed + ": " + src);
            assertEquals(Hm.typeOf(src), Hm.typeOf(once), "seed " + seed);
        }
    }

    @Test
    void addingOneToAnythingButAnIntIsAMismatch() {
        int checked = 0;
        for (long seed = 1; seed <= CASES; seed++) {
            Gen g = new Gen(seed * 13);
            T target = g.type(3);
            if (target instanceof TInt) {
                continue;
            }
            String src = "(" + g.term(target, 4) + ") + 1";
            TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf(src), "seed " + seed + ": " + src);
            assertEquals(TypeException.Kind.MISMATCH, e.kind(), src);
            checked++;
        }
        assertTrue(checked > CASES / 2, "checked only " + checked);
    }

    @Test
    void substitutionInvariantHoldsAfterEveryInference() {
        for (long seed = 1; seed <= CASES; seed++) {
            Gen g = new Gen(seed * 101);
            String src = g.term(g.type(3), 5);
            Infer inf = new Infer();
            inf.infer(Parser.parse(src));
            inf.checkInvariant(); // throws IllegalStateException on a cyclic or non-idempotent substitution
        }
    }

    @Test
    void invariantCheckerCatchesACyclicSubstitution() {
        Infer inf = new Infer();
        Type.TVar a = inf.fresh();
        Type.TVar b = inf.fresh();
        inf.forceBind(a, b);
        inf.forceBind(b, a); // deliberately corrupt: a -> b -> a
        assertThrows(IllegalStateException.class, inf::checkInvariant);
    }

    @Test
    void generatorIsDeterministicAndVaried() {
        Gen g1 = new Gen(5);
        Gen g2 = new Gen(5);
        T t1 = g1.type(3);
        assertEquals(g1.term(t1, 4), g2.term(g2.type(3), 4));
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (long seed = 1; seed <= 100; seed++) {
            Gen g = new Gen(seed);
            distinct.add(g.term(g.type(3), 4));
        }
        assertTrue(distinct.size() > 80, "only " + distinct.size() + " distinct terms");
    }
}
