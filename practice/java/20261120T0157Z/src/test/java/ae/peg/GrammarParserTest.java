package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GrammarParserTest {
    @Test
    void parsesRulesAcrossLinesWithCommentsAndKeepsOrder() {
        Grammar g = Grammar.parse("""
                # a comment
                Start <- Item (',' Item)*   # trailing comment
                         / 'none'
                Item  <- [a-z]+
                """);
        assertEquals(List.of("Start", "Item"), List.copyOf(g.rules().keySet()));
        assertEquals("Start", g.start());
        assertEquals(new Expr.Choice(List.of(
                new Expr.Seq(List.of(new Expr.Ref("Item"),
                        new Expr.Repeat(new Expr.Seq(List.of(new Expr.Lit(","), new Expr.Ref("Item"))), false))),
                new Expr.Lit("none"))), g.rules().get("Start"));
        assertEquals(new Expr.Repeat(new Expr.CharClass(false, List.of(new Expr.Range('a', 'z'))), true),
                g.rules().get("Item"));
    }

    @Test
    void prefixBindsLooserThanSuffix() {
        Grammar g = Grammar.parse("S <- !'a'* . &'b' ?");
        // !('a'*) then Any then &('b'?) -- the suffix attaches to the primary, the predicate to the whole suffix form
        assertEquals(new Expr.Seq(List.of(
                new Expr.Pred(new Expr.Repeat(new Expr.Lit("a"), false), true),
                new Expr.Any(),
                new Expr.Pred(new Expr.Opt(new Expr.Lit("b")), false))), g.rules().get("S"));
    }

    @Test
    void literalAndClassEscapes() {
        Grammar g = Grammar.parse("S <- 'it\\'s' \"q\\\"\\n\\t\" [\\]\\-a\\\\] [^x-z]");
        List<Expr> items = ((Expr.Seq) g.rules().get("S")).items();
        assertEquals(new Expr.Lit("it's"), items.get(0));
        assertEquals(new Expr.Lit("q\"\n\t"), items.get(1));
        assertEquals(new Expr.CharClass(false, List.of(new Expr.Range(']', ']'), new Expr.Range('-', '-'),
                new Expr.Range('a', 'a'), new Expr.Range('\\', '\\'))), items.get(2));
        assertEquals(new Expr.CharClass(true, List.of(new Expr.Range('x', 'z'))), items.get(3));
    }

    @Test
    void trailingDashInClassIsLiteral() {
        Grammar g = Grammar.parse("S <- [a-]");
        assertEquals(new Expr.CharClass(false, List.of(new Expr.Range('a', 'a'), new Expr.Range('-', '-'))),
                g.rules().get("S"));
    }

    @Test
    void renderRoundTripsStructurally() {
        String text = """
                Expr  <- Term (('+' / '-') Term)* / !'x' (Term Term)?
                Term  <- [0-9]+ / '(' Expr ')' / &[a-c] . / ('a' / 'b')* / (!'q' .)+
                Weird <- 'it\\'s' [\\]\\-^\\\\a-c] [^\\n\\t] ('' / 'x') ((Expr))
                """;
        Grammar g = Grammar.parse(text);
        Grammar again = Grammar.parse(g.toString());
        assertEquals(g.rules(), again.rules());
        assertEquals(g.toString(), again.toString());
    }

    @Test
    void errorsCarryLineAndColumn() {
        assertMessage("S <- 'abc", "unterminated literal at line 1, column 10");
        assertMessage("A <- 'a'\nB <- ('b'", "expected ')' at line 2, column 10");
        assertMessage("B 'b'", "expected '<-' after rule name 'B' at line 1, column 3");
        assertMessage("A <- 'a'\n<- 'b'", "expected rule name at line 2, column 1");
        // a bare name after a complete rule is read as a reference in that rule, so analysis reports it
        assertMessage("A <- 'a'\nB 'b'", "undefined rule 'B' referenced in rule 'A'");
        assertMessage("S <- [a-", "unterminated character class at line 1, column 9");
        assertMessage("S <- [z-a]", "empty range 'z-a' at line 1, column 10");
        assertMessage("S <- []", "empty character class at line 1, column 8");
        assertMessage("S <- 'a' / ", "expected expression at line 1, column 12");
        assertMessage("S <- '\\q'", "unknown escape '\\q' at line 1, column 9");
        assertMessage("S <- )", "expected expression at line 1, column 6");
        assertMessage("S <- 'a'\nS <- 'b'", "duplicate rule 'S' at line 2, column 9");
        assertMessage("   \n# only a comment", "grammar has no rules");
        assertMessage("1 <- 'a'", "expected rule name at line 1, column 1");
    }

    @Test
    void ruleBoundaryIsTheNextArrowNotTheNewline() {
        Grammar g = Grammar.parse("A <- B\nB <- 'x'");
        assertEquals(new Expr.Ref("B"), g.rules().get("A"));
        assertTrue(g.rules().containsKey("B"));
    }

    private static void assertMessage(String text, String message) {
        GrammarException e = assertThrows(GrammarException.class, () -> Grammar.parse(text), text);
        assertEquals(message, e.getMessage());
    }
}
