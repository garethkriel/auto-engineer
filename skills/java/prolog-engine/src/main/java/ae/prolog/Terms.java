package ae.prolog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Static helpers over terms: dereferencing, ordering, copying, lists and text output. */
public final class Terms {
    public static final Atom NIL = new Atom("[]");

    private Terms() {}

    public static Term deref(Term t) {
        while (t instanceof Var v && v.ref != null) t = v.ref;
        return t;
    }

    // ------------------------------------------------------------------ lists

    public static Term cons(Term head, Term tail) {
        return Struct.adopt(".", new Term[] {head, tail});
    }

    public static Term list(List<? extends Term> items) {
        return list(items, NIL);
    }

    public static Term list(List<? extends Term> items, Term tail) {
        Term result = tail;
        for (int i = items.size() - 1; i >= 0; i--) result = cons(items.get(i), result);
        return result;
    }

    static boolean isCons(Term t) {
        return t instanceof Struct s && s.arity() == 2 && s.name().equals(".");
    }

    /** The elements of a proper list, or {@link PrologError} (INSTANTIATION for a partial list, TYPE otherwise). */
    public static List<Term> toJavaList(Term list) {
        List<Term> out = new ArrayList<>();
        Term t = deref(list);
        while (isCons(t)) {
            Struct s = (Struct) t;
            out.add(s.arg(0));
            t = deref(s.arg(1));
        }
        if (t instanceof Var) throw new PrologError(PrologError.Kind.INSTANTIATION, "partial list");
        if (!(t instanceof Atom a) || !a.name().equals("[]")) {
            throw new PrologError(PrologError.Kind.TYPE, "list expected");
        }
        return out;
    }

    // ------------------------------------------------------------------ ordering

    private static int rank(Term t) {
        if (t instanceof Var) return 0;
        if (t instanceof Int) return 1;
        if (t instanceof Atom) return 2;
        return 3;
    }

    /** Standard order: Var (by creation) &lt; Int &lt; Atom (alphabetical) &lt; compound (arity, name, arguments left to right). */
    public static int compare(Term a, Term b) {
        while (true) {
            a = deref(a);
            b = deref(b);
            if (a == b) return 0;
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            switch (ra) {
                case 0: return Long.compare(((Var) a).id, ((Var) b).id);
                case 1: return Long.compare(((Int) a).value(), ((Int) b).value());
                case 2: return ((Atom) a).name().compareTo(((Atom) b).name());
                default: break;
            }
            Struct sa = (Struct) a, sb = (Struct) b;
            if (sa.arity() != sb.arity()) return Integer.compare(sa.arity(), sb.arity());
            int c = sa.name().compareTo(sb.name());
            if (c != 0) return c;
            int n = sa.arity();
            for (int i = 0; i < n - 1; i++) {
                c = compare(sa.arg(i), sb.arg(i));
                if (c != 0) return c;
            }
            a = sa.arg(n - 1);
            b = sb.arg(n - 1);
        }
    }

    // ------------------------------------------------------------------ copying

    /** Largest term (in nodes) one copy may build; guards against cyclic terms made without the occurs check. */
    static final long COPY_BUDGET = 2_000_000L;

    /** Deep copy that resolves bindings and maps every unbound variable to a fresh one (shared within one map). */
    static Term copy(Term t, Map<Var, Var> map) {
        return copy(t, map, new long[] {COPY_BUDGET});
    }

    private static Term copy(Term t, Map<Var, Var> map, long[] budget) {
        t = deref(t);
        if (t instanceof Var v) return map.computeIfAbsent(v, k -> new Var(k.name));
        if (!(t instanceof Struct s)) return t;
        Struct root = null;
        Term[] prevArgs = null;
        while (true) {
            if (--budget[0] < 0) {
                throw new PrologError(PrologError.Kind.RESOURCE, "term too large or cyclic (no occurs check)");
            }
            int n = s.arity();
            Term[] na = new Term[n];
            Struct ns = Struct.adopt(s.name(), na);
            if (prevArgs == null) root = ns;
            else prevArgs[prevArgs.length - 1] = ns;
            for (int i = 0; i < n - 1; i++) na[i] = copy(s.arg(i), map, budget);
            Term last = deref(s.arg(n - 1));
            if (last instanceof Struct ls) {
                prevArgs = na;
                s = ls;
            } else {
                na[n - 1] = last instanceof Var v ? map.computeIfAbsent(v, k -> new Var(k.name)) : last;
                return root;
            }
        }
    }

    static Term copy(Term t) {
        return copy(t, new IdentityHashMap<>());
    }

    // ------------------------------------------------------------------ output

    /** Text form. Unbound variables print as {@code _G<id>}. Parsing the text gives back an equal term. */
    public static String format(Term t) {
        return new Writer(null).write(t);
    }

    /** Like {@link #format} but variables are named {@code _V0, _V1, ...} by first appearance. */
    public static String formatCanonical(Term t) {
        return new Writer(new IdentityHashMap<>()).write(t);
    }

    private static boolean plainAtom(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c >= 'a' && c <= 'z') {
            for (int i = 1; i < s.length(); i++) {
                char d = s.charAt(i);
                if (!(Character.isLetterOrDigit(d) && d < 128 || d == '_')) return false;
            }
            return true;
        }
        if (s.equals("[]") || s.equals("!") || s.equals(";")) return true;
        for (int i = 0; i < s.length(); i++) if (!Ops.isSymbolChar(s.charAt(i))) return false;
        return true;
    }

    static String atomText(String name) {
        return plainAtom(name) ? name : "'" + name.replace("'", "''") + "'";
    }

    private static final class Writer {
        private final Map<Var, Integer> names;
        private final StringBuilder sb = new StringBuilder();

        Writer(Map<Var, Integer> names) {
            this.names = names;
        }

        String write(Term t) {
            emit(t, 1200);
            return sb.toString();
        }

        /** Appends a token, inserting a space where two tokens would otherwise fuse. */
        private void put(String piece) {
            if (sb.length() > 0 && !piece.isEmpty()) {
                char last = sb.charAt(sb.length() - 1), first = piece.charAt(0);
                boolean bothSym = Ops.isSymbolChar(last) && Ops.isSymbolChar(first);
                boolean bothAlnum = (Character.isLetterOrDigit(last) || last == '_') && (Character.isLetterOrDigit(first) || first == '_');
                if (bothSym || bothAlnum) sb.append(' ');
            }
            sb.append(piece);
        }

        private void emit(Term t, int max) {
            t = deref(t);
            if (t instanceof Int i) {
                if (i.value() < 0 && max < 999) {
                    put("(");
                    put(Long.toString(i.value()));
                    sb.append(')');
                } else {
                    put(Long.toString(i.value()));
                }
            } else if (t instanceof Var v) {
                if (names == null) put("_G" + v.id);
                else put("_V" + names.computeIfAbsent(v, k -> names.size()));
            } else if (t instanceof Atom a) {
                boolean isOp = Ops.INFIX.containsKey(a.name()) || Ops.PREFIX.containsKey(a.name());
                if (isOp && max < 999) {
                    put("(");
                    put(atomText(a.name()));
                    sb.append(')');
                } else {
                    put(atomText(a.name()));
                }
            } else {
                emitStruct((Struct) t, max);
            }
        }

        private void emitStruct(Struct s, int max) {
            if (isCons(s)) {
                emitList(s);
                return;
            }
            Ops.Op op = s.arity() == 2 ? Ops.INFIX.get(s.name()) : null;
            if (op == null) {
                put(atomText(s.name()));
                sb.append('(');
                for (int i = 0; i < s.arity(); i++) {
                    if (i > 0) sb.append(',');
                    emit(s.arg(i), 999);
                }
                sb.append(')');
                return;
            }
            boolean paren = op.prec() > max;
            if (paren) {
                put("(");
            }
            emit(s.arg(0), op.leftMax());
            String name = s.name();
            if (name.equals(",")) {
                sb.append(',');
            } else if (Character.isLetter(name.charAt(0))) {
                sb.append(' ').append(name).append(' ');
            } else {
                put(name);
            }
            emit(s.arg(1), op.rightMax());
            if (paren) sb.append(')');
        }

        private void emitList(Struct s) {
            put("[");
            Term t = s;
            boolean first = true;
            while (isCons(t)) {
                Struct c = (Struct) t;
                if (!first) sb.append(',');
                first = false;
                emit(c.arg(0), 999);
                t = deref(c.arg(1));
            }
            if (!(t instanceof Atom a && a.name().equals("[]"))) {
                sb.append('|');
                emit(t, 999);
            }
            sb.append(']');
        }
    }
}
