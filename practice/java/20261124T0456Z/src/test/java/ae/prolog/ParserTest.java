package ae.prolog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ParserTest {
    private static Term p(String s) {
        return Parser.parseTerm(s);
    }

    @Test
    void precedenceMultiplicationBindsTighter() {
        Struct expected = new Struct("+", new Int(1), new Struct("*", new Int(2), new Int(3)));
        assertEquals(expected, p("1+2*3"));
    }

    @Test
    void subtractionIsLeftAssociative() {
        Struct expected = new Struct("-", new Struct("-", new Int(10), new Int(3)), new Int(2));
        assertEquals(expected, p("10-3-2"));
    }

    @Test
    void commaIsRightAssociativeAndAboveArgumentLevel() {
        assertEquals(new Struct(",", new Atom("a"), new Struct(",", new Atom("b"), new Atom("c"))), p("(a,b,c)"));
        Struct f = (Struct) p("f(a,b,c)");
        assertEquals(3, f.arity());
    }

    @Test
    void negativeLiteralVersusSubtraction() {
        assertEquals(new Int(-5), p("-5"));
        assertEquals(new Struct("-", new Int(3), new Int(5)), p("3-5"));
        assertEquals(new Struct("-", new Int(3), new Int(-5)), p("3- -5"));
        assertEquals(new Struct("-", new Int(5)), p("-(5)"));
        assertEquals(new Struct("-", new Int(5)), p("- 5"));
    }

    @Test
    void listsWithTail() {
        Term t = p("[1,2|T]");
        assertEquals("[1,2|_V0]", Terms.formatCanonical(t));
        assertEquals(new Atom("[]"), p("[]"));
        assertEquals(new Struct(".", new Int(1), new Atom("[]")), p("[1]"));
    }

    @Test
    void queryVariablesAreSharedByNameAndUnderscoreIsFresh() {
        Struct s = (Struct) p("f(X,X,_,_)");
        assertSame(s.arg(0), s.arg(1));
        assertNotSame(s.arg(2), s.arg(3));
    }

    @Test
    void quotedAtomsAndComments() {
        assertEquals(new Atom("Hello World"), p("'Hello World'"));
        assertEquals(new Atom("it's"), p("'it''s'"));
        assertEquals(new Struct("f", new Atom("a")), p("f(a) % trailing"));
        assertEquals(new Struct("f", new Atom("a")), p("f(/* inner */ a)"));
    }

    @Test
    void programClausesAndFacts() {
        List<Clause> cs = Parser.parseProgram("a. b :- a, \\+ c.\n% c\nd(X) :- ( X > 1 -> e ; f ).");
        assertEquals(3, cs.size());
        assertEquals(new Atom("true"), cs.get(0).body());
        assertEquals("b", Terms.format(cs.get(1).head()));
        assertEquals("a,\\+(c)", Terms.format(cs.get(1).body()));
        assertEquals("_V0>1->e;f", Terms.formatCanonical(cs.get(2).body()));
    }

    @Test
    void syntaxErrorsCarryLineAndColumn() {
        PrologError e = assertThrows(PrologError.class, () -> Parser.parseProgram("a.\nb :- c d.\n"));
        assertEquals(PrologError.Kind.SYNTAX, e.kind());
        assertTrue(e.getMessage().contains("line 2, column 8"), e.getMessage());
    }

    @Test
    void unterminatedConstructsAreSyntaxErrors() {
        for (String bad : new String[] {"f(a", "[1,2", "'abc", "a :- .", "foo bar", "X = ", "f(a,)", "1 +", "/* x"}) {
            PrologError e = assertThrows(PrologError.class, () -> Parser.parseTerm(bad), bad);
            assertEquals(PrologError.Kind.SYNTAX, e.kind(), bad);
        }
    }

    @Test
    void integerOverflowInLiteralIsSyntaxError() {
        assertEquals(new Int(Long.MAX_VALUE), p("9223372036854775807"));
        assertThrows(PrologError.class, () -> p("9223372036854775808"));
    }

    @Test
    void formatQuotesAtomsThatNeedIt() {
        assertEquals("'Hello World'", Terms.format(new Atom("Hello World")));
        assertEquals("'it''s'", Terms.format(new Atom("it's")));
        assertEquals("abc_1", Terms.format(new Atom("abc_1")));
        assertEquals("'A'", Terms.format(new Atom("A")));
        assertEquals("'1a'", Terms.format(new Atom("1a")));
        assertEquals("[]", Terms.format(new Atom("[]")));
        assertEquals("1-(-2)", Terms.format(p("1 - -2")));
        assertEquals("(1+2)*3", Terms.format(p("(1+2)*3")));
        assertEquals("1-(2-3)", Terms.format(p("1-(2-3)")));
        assertEquals("X is 1 mod 2".replace("X", "a"), Terms.format(p("a is 1 mod 2")));
    }

    // ---- randomized: parse(format(t)) must be the same term (up to variable names) ----
    private static final String[] ATOMS = {"a", "b", "foo", "Hello World", "it's", "[]", "x1", "+", "-", "Z", "end"};
    private static final String[] OPS = {"+", "-", "*", "//", "mod", "rem", "=", "\\=", "==", "is", "<", ">=", ",", ";", "->", ":-"};

    private Term gen(Random r, int depth, List<Var> vars) {
        int k = depth == 0 ? r.nextInt(4) : r.nextInt(8);
        switch (k) {
            case 0: return new Atom(ATOMS[r.nextInt(ATOMS.length)]);
            case 1: return new Int(r.nextInt(5) == 0 ? -r.nextInt(100) : r.nextInt(1000));
            case 2: {
                if (vars.isEmpty() || r.nextInt(3) == 0) vars.add(new Var("V"));
                return vars.get(r.nextInt(vars.size()));
            }
            case 3: return new Int(r.nextBoolean() ? Long.MIN_VALUE + r.nextInt(3) : Long.MAX_VALUE - r.nextInt(3));
            case 4: case 5: {
                String op = OPS[r.nextInt(OPS.length)];
                return new Struct(op, gen(r, depth - 1, vars), gen(r, depth - 1, vars));
            }
            case 6: {
                int n = r.nextInt(4);
                Term[] args = new Term[n + 1];
                for (int i = 0; i <= n; i++) args[i] = gen(r, depth - 1, vars);
                String f = r.nextBoolean() ? "f" : (r.nextBoolean() ? "-" : "\\+");
                return new Struct(f, args);
            }
            default: {
                int n = r.nextInt(4);
                List<Term> items = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) items.add(gen(r, depth - 1, vars));
                Term tail = r.nextInt(4) == 0 ? gen(r, depth - 1, vars) : new Atom("[]");
                return Terms.list(items, tail);
            }
        }
    }

    @Test
    void randomTermsRoundTripThroughFormatAndParse() {
        Random r = new Random(20261124L);
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            Term t = gen(r, 4, new java.util.ArrayList<>());
            String text = Terms.formatCanonical(t);
            Term back;
            try {
                back = Parser.parseTerm(text);
            } catch (PrologError e) {
                fail("could not reparse " + text + ": " + e.getMessage());
                return;
            }
            assertEquals(text, Terms.formatCanonical(back), "round trip changed " + text);
            checked++;
        }
        assertEquals(3000, checked);
    }
}
