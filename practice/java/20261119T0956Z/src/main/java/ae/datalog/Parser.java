package ae.datalog;

import ae.datalog.Syntax.*;
import java.util.ArrayList;
import java.util.List;

/** Tokeniser and recursive-descent parser. */
final class Parser {
    private enum K { IDENT, VAR, NUM, STR, LPAREN, RPAREN, COMMA, DOT, IMPLIES, QUERY, NEQ, EOF }

    private record Tok(K kind, String text, int line, int col) {}

    private final List<Tok> toks = new ArrayList<>();
    private int pos;
    private int anon;

    private Parser(String src) {
        lex(src);
    }

    static Program parse(String src) {
        return new Parser(src).program();
    }

    private void lex(String s) {
        int i = 0;
        int line = 1;
        int col = 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            int startCol = col;
            if (c == '\n') {
                i++;
                line++;
                col = 1;
            } else if (Character.isWhitespace(c)) {
                i++;
                col++;
            } else if (c == '%') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                    col++;
                }
            } else if (c == '(' || c == ')' || c == ',' || c == '.') {
                K k = c == '(' ? K.LPAREN : c == ')' ? K.RPAREN : c == ',' ? K.COMMA : K.DOT;
                toks.add(new Tok(k, String.valueOf(c), line, col));
                i++;
                col++;
            } else if (s.startsWith(":-", i)) {
                toks.add(new Tok(K.IMPLIES, ":-", line, col));
                i += 2;
                col += 2;
            } else if (s.startsWith("?-", i)) {
                toks.add(new Tok(K.QUERY, "?-", line, col));
                i += 2;
                col += 2;
            } else if (s.startsWith("!=", i)) {
                toks.add(new Tok(K.NEQ, "!=", line, col));
                i += 2;
                col += 2;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < s.length() && s.charAt(j) != '\n') {
                    char d = s.charAt(j);
                    if (d == '\\' && j + 1 < s.length() && s.charAt(j + 1) == '"') {
                        sb.append('"');
                        j += 2;
                    } else if (d == '"') {
                        closed = true;
                        j++;
                        break;
                    } else {
                        sb.append(d);
                        j++;
                    }
                }
                if (!closed) {
                    throw new DatalogException(line, col, "unterminated string");
                }
                toks.add(new Tok(K.STR, sb.toString(), line, col));
                col += j - i;
                i = j;
            } else if (Character.isDigit(c) || c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < s.length() && Character.isDigit(s.charAt(j))) {
                    j++;
                }
                toks.add(new Tok(K.NUM, s.substring(i, j), line, col));
                col += j - i;
                i = j;
            } else if (isIdentStart(c)) {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                String w = s.substring(i, j);
                toks.add(new Tok(Character.isLowerCase(c) ? K.IDENT : K.VAR, w, line, col));
                col += j - i;
                i = j;
            } else {
                throw new DatalogException(line, startCol, "unexpected character '" + c + "'");
            }
        }
        toks.add(new Tok(K.EOF, "<end of input>", line, col));
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c) && c < 128;
    }

    private Tok peek() {
        return toks.get(pos);
    }

    private Tok next() {
        return toks.get(pos++);
    }

    private Tok expect(K k, String what) {
        Tok t = peek();
        if (t.kind != k) {
            throw new DatalogException(t.line, t.col, "expected " + what + " but found '" + t.text + "'");
        }
        return next();
    }

    private Program program() {
        List<Atom> facts = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<Atom> queries = new ArrayList<>();
        while (peek().kind != K.EOF) {
            if (peek().kind == K.QUERY) {
                next();
                queries.add(atom());
                expect(K.DOT, "'.'");
                continue;
            }
            Tok start = peek();
            Atom head = atom();
            if (peek().kind == K.IMPLIES) {
                next();
                List<Item> body = new ArrayList<>();
                body.add(item());
                while (peek().kind == K.COMMA) {
                    next();
                    body.add(item());
                }
                expect(K.DOT, "'.' or ','");
                rules.add(new Rule(head, List.copyOf(body), new Pos(start.line, start.col)));
            } else {
                expect(K.DOT, "'.' or ':-'");
                for (Term t : head.args()) {
                    if (t instanceof Var) {
                        throw new DatalogException(start.line, start.col, "fact must be ground: " + head.pred());
                    }
                }
                facts.add(head);
            }
        }
        return new Program(List.copyOf(facts), List.copyOf(rules), List.copyOf(queries));
    }

    private Item item() {
        Tok t = peek();
        if (t.kind == K.IDENT && t.text.equals("not") && toks.get(pos + 1).kind == K.IDENT) {
            next();
            return new Neg(atom());
        }
        if ((t.kind == K.VAR || t.kind == K.NUM || t.kind == K.STR)) {
            Term l = term();
            expect(K.NEQ, "'!='");
            return new Neq(l, term());
        }
        Atom a = atom();
        return new Pos_(a);
    }

    private Atom atom() {
        Tok name = expect(K.IDENT, "predicate name");
        expect(K.LPAREN, "'('");
        List<Term> args = new ArrayList<>();
        args.add(term());
        while (peek().kind == K.COMMA) {
            next();
            args.add(term());
        }
        expect(K.RPAREN, "')' or ','");
        return new Atom(name.text, List.copyOf(args), new Pos(name.line, name.col));
    }

    private Term term() {
        Tok t = next();
        return switch (t.kind) {
            case IDENT, NUM, STR -> new Const(t.text);
            case VAR -> t.text.equals("_") ? new Var("_" + (++anon)) : new Var(t.text);
            default -> throw new DatalogException(t.line, t.col, "expected a term but found '" + t.text + "'");
        };
    }
}
