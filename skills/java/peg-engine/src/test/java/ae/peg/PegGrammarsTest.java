package ae.peg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PegGrammarsTest {
    static final String ARITH = """
            Sum    <- Product (AddOp Product)*
            Product <- Atom (MulOp Atom)*
            Atom   <- Number / '(' Sum ')'
            AddOp  <- [+\\-]
            MulOp  <- [*/]
            Number <- [0-9]+
            """;

    static final String JSONISH = """
            Value  <- WS (Object / Array / String / Number / Lit) WS
            Object <- '{' WS (Pair (',' Pair)*)? WS '}'
            Pair   <- WS String WS ':' Value
            Array  <- '[' WS (Value (',' Value)*)? ']'
            String <- '"' ([^"\\\\] / '\\\\' .)* '"'
            Number <- '-'? ('0' / [1-9][0-9]*) ('.' [0-9]+)?
            Lit    <- 'true' / 'false' / 'null'
            WS     <- [ \\t\\n]*
            """;

    @Test
    void arithmeticTreeShowsPrecedenceAndAssociativityPositions() {
        PegParser p = new PegParser(Grammar.parse(ARITH));
        String in = "1+2*(3-4)";
        assertEquals("(Sum (Product (Atom (Number \"1\"))) (AddOp \"+\") "
                + "(Product (Atom (Number \"2\")) (MulOp \"*\") "
                + "(Atom (Sum (Product (Atom (Number \"3\"))) (AddOp \"-\") (Product (Atom (Number \"4\")))))))",
                p.parse(in).sexp(in));
    }

    @Test
    void nodeSpansAndFind() {
        PegParser p = new PegParser(Grammar.parse(ARITH));
        String in = "12*3+45";
        Node root = p.parse(in);
        List<Node> nums = root.find("Number");
        assertEquals(List.of("12", "3", "45"), nums.stream().map(n -> n.text(in)).toList());
        assertEquals(List.of(0, 3, 5), nums.stream().map(Node::start).toList());
        assertEquals(0, root.start());
        assertEquals(in.length(), root.end());
    }

    @Test
    void jsonishAcceptsValidDocuments() {
        PegParser p = new PegParser(Grammar.parse(JSONISH));
        for (String ok : List.of("null", " true ", "-0.5", "[]", "[1, 2 ,[3]]", "{\"a\": [true, {\"b\": null}], \"c\": \"x\\\"y\"}",
                "{ }", "\"\"", "10", "[ ]", "  {\"k\":\"v\"}\n")) {
            assertDoesNotThrow(() -> p.parse(ok), ok);
        }
    }

    @Test
    void jsonishRejectsInvalidDocuments() {
        PegParser p = new PegParser(Grammar.parse(JSONISH));
        for (String bad : List.of("", "nul", "[1,]", "{\"a\" 1}", "01", "\"abc", "[1 2]", "{\"a\":1,}", "tru", "[true] x", "1.")) {
            assertThrows(PegParseException.class, () -> p.parse(bad), bad);
        }
    }

    @Test
    void balancedParensRecursion() {
        PegParser p = new PegParser(Grammar.parse("S <- ('(' S ')')*"));
        assertDoesNotThrow(() -> p.parse("(()(()))()"));
        assertThrows(PegParseException.class, () -> p.parse("(()"));
        String deep = "(".repeat(400) + ")".repeat(400);
        assertEquals(800, p.parse(deep).end());
    }

    @Test
    void identifiersWithKeywordExclusionUsePredicates() {
        PegParser p = new PegParser(Grammar.parse("""
                Id      <- !Keyword [a-z]+
                Keyword <- ('if' / 'else') ![a-z]
                """));
        assertEquals(3, p.matchPrefix("abc").orElseThrow().end());
        assertEquals(4, p.matchPrefix("iffy").orElseThrow().end());
        assertEquals(true, p.matchPrefix("if").isEmpty());
        assertEquals(true, p.matchPrefix("else").isEmpty());
        assertEquals(5, p.matchPrefix("elsey").orElseThrow().end());
    }
}
