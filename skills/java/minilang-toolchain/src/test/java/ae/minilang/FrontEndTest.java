package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.minilang.LangException.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrontEndTest {
    private static String show(String src) {
        return Printer.source(Parser.parse(src));
    }

    private static String mainOf(String src) {
        return show(src).trim();
    }

    private static LangException fails(String src) {
        return assertThrows(LangException.class, () -> Checker.check(Parser.parse(src)), src);
    }

    @Test
    void lexerPositionsAndComments() {
        List<Lexer.Token> t = Lexer.lex("let x = 1; // c\n  y");
        assertEquals("let", t.get(0).text());
        assertEquals(1, t.get(0).col());
        Lexer.Token y = t.get(5);
        assertEquals("y", y.text());
        assertEquals(2, y.line());
        assertEquals(3, y.col());
        assertEquals(Lexer.Type.EOF, t.get(6).type());
    }

    @Test
    void lexerRejectsStrayCharacters() {
        LangException e = assertThrows(LangException.class, () -> Lexer.lex("a & b"));
        assertEquals(Kind.SYNTAX, e.kind());
        assertTrue(e.getMessage().startsWith("line 1, col 3"), e.getMessage());
        assertThrows(LangException.class, () -> Lexer.lex("12ab"));
    }

    @Test
    void precedenceMultiplicationOverAddition() {
        assertEquals("(1 + (2 * 3));", mainOf("1 + 2 * 3;"));
        assertEquals("((1 * 2) + 3);", mainOf("1 * 2 + 3;"));
    }

    @Test
    void additionIsLeftAssociative() {
        assertEquals("((1 - 2) - 3);", mainOf("1 - 2 - 3;"));
        assertEquals("((8 / 4) / 2);", mainOf("8 / 4 / 2;"));
    }

    @Test
    void logicalAndBindsTighterThanOr() {
        assertEquals("(1 || (2 && 3));", mainOf("1 || 2 && 3;"));
        assertEquals("((1 && 2) || 3);", mainOf("1 && 2 || 3;"));
    }

    @Test
    void comparisonBetweenArithmeticAndEquality() {
        assertEquals("((1 + 2) < (3 * 4));", mainOf("1 + 2 < 3 * 4;"));
        assertEquals("((1 < 2) == (3 < 4));", mainOf("1 < 2 == 3 < 4;"));
    }

    @Test
    void unaryBindsTighterThanBinary() {
        assertEquals("((-1) * 2);", mainOf("-1 * 2;"));
        assertEquals("(!(1 + 2));", mainOf("!(1 + 2);"));
        assertEquals("((!1) + 2);", mainOf("!1 + 2;"));
        assertEquals("(-(-3));", mainOf("--3;"));
    }

    @Test
    void minimumLiteralParsesAndOnePastItIsRejected() {
        assertEquals("(-9223372036854775808);", mainOf("-9223372036854775808;"));
        LangException e = assertThrows(LangException.class, () -> Parser.parse("9223372036854775808;"));
        assertEquals(Kind.SYNTAX, e.kind());
        assertEquals("9223372036854775807;", mainOf("9223372036854775807;"));
    }

    @Test
    void callsAssignmentsAndElseIf() {
        assertEquals("f(1, (2 + 3));", mainOf("f(1, 2+3);"));
        assertEquals("g();", mainOf("g();"));
        String s = show("if (a) { x = 1; } else if (b) { x = 2; } else { x = 3; }");
        assertTrue(s.contains("if (a) {"), s);
        assertTrue(s.contains("else { if (b) {"), s);
    }

    @Test
    void functionsAreHoistedOutOfMainOrder() {
        Ast.Program p = Parser.parse("print 1; fn f() { return 2; } print 3;");
        assertEquals(1, p.funcs().size());
        assertEquals(2, p.main().size());
    }

    @Test
    void syntaxErrorsCarryPositions() {
        LangException e = assertThrows(LangException.class, () -> Parser.parse("let x = ;"));
        assertEquals(Kind.SYNTAX, e.kind());
        assertTrue(e.getMessage().startsWith("line 1, col 9"), e.getMessage());
        e = assertThrows(LangException.class, () -> Parser.parse("print 1\nprint 2;"));
        assertTrue(e.getMessage().startsWith("line 2, col 1"), e.getMessage());
        assertThrows(LangException.class, () -> Parser.parse("let if = 1;"));
        assertThrows(LangException.class, () -> Parser.parse("{ fn f() {} }"));
        assertThrows(LangException.class, () -> Parser.parse("x + 1 = 2;"));
        assertThrows(LangException.class, () -> Parser.parse("if (1) print 1;"));
    }

    @Test
    void checkerRejectsUnknownAndDuplicateNames() {
        assertEquals(Kind.NAME, fails("print x;").kind());
        assertEquals(Kind.NAME, fails("x = 1;").kind());
        assertEquals(Kind.NAME, fails("let x = 1; let x = 2;").kind());
        assertEquals(Kind.NAME, fails("f();").kind());
        assertEquals(Kind.NAME, fails("fn f() {} fn f() {}").kind());
        assertEquals(Kind.NAME, fails("fn f(a, a) {}").kind());
        assertEquals(Kind.NAME, fails("fn f(a) { let a = 1; }").kind());
        assertEquals(Kind.NAME, fails("fn f() { print y; } let y = 1;").kind());
    }

    @Test
    void checkerArityAndPositions() {
        LangException e = fails("fn f(a, b) { return a; }\nprint f(1);");
        assertEquals(Kind.ARITY, e.kind());
        assertTrue(e.getMessage().startsWith("line 2, col 7"), e.getMessage());
        e = fails("let a = 1;\n  print b;");
        assertTrue(e.getMessage().startsWith("line 2, col 9"), e.getMessage());
    }

    @Test
    void checkerScopingRules() {
        Checker.check(Parser.parse("let x = 1; { let x = 2; } let y = x + 1;"));
        Checker.check(Parser.parse("let x = 1; let x2 = 2; { let x = x + 1; print x; }"));
        Checker.check(Parser.parse("fn even(n) { if (n == 0) { return 1; } return odd(n - 1); }"
                + "fn odd(n) { if (n == 0) { return 0; } return even(n - 1); } print even(4);"));
        assertEquals(Kind.NAME, fails("{ let t = 1; } print t;").kind());
        assertEquals(Kind.NAME, fails("while (0) { let t = 1; } print t;").kind());
    }
}
