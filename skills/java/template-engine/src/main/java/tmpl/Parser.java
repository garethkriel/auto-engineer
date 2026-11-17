package tmpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Recursive-descent block parser: turns lexer tokens into the {@link Node} tree. */
final class Parser {
    private static final Set<String> BLOCK_KEYWORDS = Set.of("elif", "else", "endif", "endfor");
    private static final Set<String> IF_STOPS = Set.of("elif", "else", "endif");
    private static final Set<String> FOR_STOPS = Set.of("endfor");

    private final String src;
    private final List<Lexer.Token> tokens;
    private int idx;
    private int depth;

    private Parser(String src) {
        this.src = src;
        this.tokens = Lexer.lex(src);
    }

    static List<Node> parse(String src) {
        Parser parser = new Parser(src);
        return parser.parseBlock(Set.of());
    }

    /** Word and the index in the content where the remainder starts. */
    private record Head(String keyword, int restFrom) {
    }

    private static Head head(String content) {
        int s = 0;
        while (s < content.length() && Character.isWhitespace(content.charAt(s))) {
            s++;
        }
        int e = s;
        while (e < content.length() && !Character.isWhitespace(content.charAt(e))) {
            e++;
        }
        return new Head(content.substring(s, e), e);
    }

    /** Parses nodes until a tag whose keyword is in {@code stops} (left unconsumed) or the end of input. */
    private List<Node> parseBlock(Set<String> stops) {
        if (++depth > ExprParser.MAX_DEPTH + 1) {
            Lexer.Token at = tokens.get(Math.max(0, idx - 1));
            throw Positions.syntax(src, at.openOffset(), "blocks nested too deeply");
        }
        List<Node> nodes = new ArrayList<>();
        while (idx < tokens.size()) {
            Lexer.Token tok = tokens.get(idx);
            switch (tok.kind()) {
                case TEXT -> {
                    nodes.add(new Node.Text(tok.text()));
                    idx++;
                }
                case OUTPUT -> {
                    ExprParser ep = new ExprParser(src, tok.text(), 0, tok.contentOffset());
                    nodes.add(ep.parseOutput());
                    idx++;
                }
                case TAG -> {
                    Head h = head(tok.text());
                    if (stops.contains(h.keyword())) {
                        depth--;
                        return nodes;
                    }
                    switch (h.keyword()) {
                        case "if" -> nodes.add(parseIf(tok, h));
                        case "for" -> nodes.add(parseFor(tok, h));
                        default -> {
                            if (BLOCK_KEYWORDS.contains(h.keyword())) {
                                throw Positions.syntax(src, tok.openOffset(),
                                        "unexpected '{% " + h.keyword() + " %}'");
                            }
                            throw Positions.syntax(src, tok.openOffset(),
                                    "unknown tag '" + (h.keyword().isEmpty() ? "" : h.keyword()) + "'");
                        }
                    }
                }
            }
        }
        depth--;
        return nodes;
    }

    private ExprParser exprOf(Lexer.Token tok, Head h) {
        return new ExprParser(src, tok.text(), h.restFrom(), tok.contentOffset());
    }

    private void noArguments(Lexer.Token tok, Head h) {
        exprOf(tok, h).expectEnd();
    }

    private Node parseIf(Lexer.Token open, Head openHead) {
        List<Node.Branch> branches = new ArrayList<>();
        List<Node> orElse = List.of();
        Expr condition = exprOf(open, openHead).parseCondition();
        idx++;
        List<Node> body = parseBlock(IF_STOPS);
        branches.add(new Node.Branch(condition, body));
        boolean sawElse = false;
        while (true) {
            if (idx >= tokens.size()) {
                throw Positions.syntax(src, open.openOffset(), "unclosed '{% if %}' (missing '{% endif %}')");
            }
            Lexer.Token tok = tokens.get(idx);
            Head h = head(tok.text());
            switch (h.keyword()) {
                case "elif" -> {
                    if (sawElse) {
                        throw Positions.syntax(src, tok.openOffset(), "'{% elif %}' after '{% else %}'");
                    }
                    Expr c = exprOf(tok, h).parseCondition();
                    idx++;
                    branches.add(new Node.Branch(c, parseBlock(IF_STOPS)));
                }
                case "else" -> {
                    if (sawElse) {
                        throw Positions.syntax(src, tok.openOffset(), "duplicate '{% else %}'");
                    }
                    noArguments(tok, h);
                    sawElse = true;
                    idx++;
                    orElse = parseBlock(IF_STOPS);
                }
                default -> { // endif
                    noArguments(tok, h);
                    idx++;
                    return new Node.If(List.copyOf(branches), orElse);
                }
            }
        }
    }

    private Node parseFor(Lexer.Token open, Head openHead) {
        ExprParser.ForHeader header = exprOf(open, openHead).parseForHeader();
        idx++;
        List<Node> body = parseBlock(FOR_STOPS);
        if (idx >= tokens.size()) {
            throw Positions.syntax(src, open.openOffset(), "unclosed '{% for %}' (missing '{% endfor %}')");
        }
        Lexer.Token end = tokens.get(idx);
        noArguments(end, head(end.text()));
        idx++;
        return new Node.For(header.variable(), header.source(), header.sourceOffset(), body);
    }
}
