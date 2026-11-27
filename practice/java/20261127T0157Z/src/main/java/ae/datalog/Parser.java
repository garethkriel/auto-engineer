package ae.datalog;

import java.util.ArrayList;
import java.util.List;

/** Hand-written recursive-descent parser. Errors are {@code line:col: message}. */
public final class Parser {
    private final String s;
    private int pos;
    private int anon;

    private Parser(String s) {
        this.s = s;
    }

    /** Parses a whole program. */
    public static Program parse(String src) {
        Parser p = new Parser(src);
        List<Rule> rules = new ArrayList<>();
        List<Atom> queries = new ArrayList<>();
        while (true) {
            p.skip();
            if (p.eof()) break;
            p.anon = 0;
            if (p.s.startsWith("?-", p.pos)) {
                p.pos += 2;
                queries.add(p.atom());
                p.expectDot();
                continue;
            }
            Atom head = p.atom();
            p.skip();
            List<Literal> body = new ArrayList<>();
            if (p.s.startsWith(":-", p.pos)) {
                p.pos += 2;
                body.add(p.literal());
                p.skip();
                while (!p.eof() && p.s.charAt(p.pos) == ',') {
                    p.pos++;
                    body.add(p.literal());
                    p.skip();
                }
            } else if (p.eof() || p.s.charAt(p.pos) != '.') {
                throw p.error("expected '.' or ':-'");
            }
            p.expectDot();
            rules.add(new Rule(head, body));
        }
        return new Program(rules, queries);
    }

    /** Parses a single atom such as {@code anc(tom, X)} (no trailing dot). */
    public static Atom parseAtom(String src) {
        Parser p = new Parser(src);
        Atom a = p.atom();
        p.skip();
        if (!p.eof()) throw p.error("unexpected trailing input");
        return a;
    }

    private boolean eof() {
        return pos >= s.length();
    }

    private DatalogException error(String msg) {
        int line = 1, col = 1;
        for (int i = 0; i < pos && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new DatalogException(line + ":" + col + ": " + msg);
    }

    private void skip() {
        while (!eof()) {
            char c = s.charAt(pos);
            if (c == '%') {
                while (!eof() && s.charAt(pos) != '\n') pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else {
                break;
            }
        }
    }

    private void expectDot() {
        skip();
        if (eof() || s.charAt(pos) != '.') throw error("expected '.'");
        pos++;
    }

    private static boolean idChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private String word() {
        int st = pos;
        while (!eof() && idChar(s.charAt(pos))) pos++;
        return s.substring(st, pos);
    }

    private Literal literal() {
        skip();
        int save = pos;
        if (s.startsWith("not", pos) && pos + 3 < s.length() && !idChar(s.charAt(pos + 3))) {
            pos += 3;
            skip();
            if (!eof() && s.charAt(pos) >= 'a' && s.charAt(pos) <= 'z') return new Literal.Neg(atom());
            throw error("expected atom after 'not'");
        }
        // either an atom (lowercase word followed by '(' or not an operator) or a comparison
        char c = eof() ? ' ' : s.charAt(pos);
        if (c >= 'a' && c <= 'z') {
            word();
            skip();
            boolean cmp = s.startsWith("=", pos) || s.startsWith("!=", pos);
            pos = save;
            if (!cmp) return new Literal.Pos(atom());
        }
        Term l = term();
        skip();
        boolean neq;
        if (s.startsWith("!=", pos)) {
            neq = true;
            pos += 2;
        } else if (s.startsWith("=", pos)) {
            neq = false;
            pos++;
        } else {
            throw error("expected '=' or '!='");
        }
        Term r = term();
        return neq ? new Literal.Neq(l, r) : new Literal.Eq(l, r);
    }

    private Atom atom() {
        skip();
        if (eof() || !(s.charAt(pos) >= 'a' && s.charAt(pos) <= 'z')) throw error("expected predicate name");
        String pred = word();
        List<Term> args = new ArrayList<>();
        skip();
        if (!eof() && s.charAt(pos) == '(') {
            pos++;
            args.add(term());
            skip();
            while (!eof() && s.charAt(pos) == ',') {
                pos++;
                args.add(term());
                skip();
            }
            if (eof() || s.charAt(pos) != ')') throw error("expected ')'");
            pos++;
        }
        return new Atom(pred, args);
    }

    private Term term() {
        skip();
        if (eof()) throw error("expected term");
        char c = s.charAt(pos);
        if (c == '_' && (pos + 1 >= s.length() || !idChar(s.charAt(pos + 1)))) {
            pos++;
            return new Term.Var("_#" + (++anon));
        }
        if ((c >= 'A' && c <= 'Z') || c == '_') return new Term.Var(word());
        if (c >= 'a' && c <= 'z') return new Term.Const(word());
        if (c == '-' || (c >= '0' && c <= '9')) {
            int st = pos;
            if (c == '-') pos++;
            int ds = pos;
            while (!eof() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            if (pos == ds) throw error("expected digits");
            String t = s.substring(st, pos);
            return new Term.Const(new java.math.BigInteger(t).toString());
        }
        throw error("expected term");
    }
}
