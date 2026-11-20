package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PegErrorTest {
    private static PegParseException fail(String grammar, String input) {
        return assertThrows(PegParseException.class, () -> new PegParser(Grammar.parse(grammar)).parse(input), input);
    }

    @Test
    void reportsFarthestFailureWithAllExpectationsThere() {
        String in = "1+2*x";
        PegParseException e = fail(PegGrammarsTest.ARITH, in);
        assertEquals(in.indexOf('x'), e.position());
        assertEquals(1, e.line());
        assertEquals(in.indexOf('x') + 1, e.column());
        // after '*' an Atom is required: a digit or '('
        assertEquals(List.of("'('", "[0-9]"), List.copyOf(e.expected()));
        assertEquals("line 1, column 5: expected '(' or [0-9], found 'x'", e.getMessage());
    }

    @Test
    void trailingGarbageIsReportedWhereTheRepetitionStopped() {
        String in = "12 ";
        PegParseException e = fail("S <- [0-9]+", in);
        assertEquals(2, e.position());
        assertEquals(List.of("[0-9]"), List.copyOf(e.expected()));
        assertEquals("line 1, column 3: expected [0-9], found ' '", e.getMessage());
    }

    @Test
    void unexpectedEndOfInput() {
        String in = "(1+";
        PegParseException e = fail(PegGrammarsTest.ARITH, in);
        assertEquals(in.length(), e.position());
        assertEquals("line 1, column 4: expected '(' or [0-9], found end of input", e.getMessage());
    }

    @Test
    void lineAndColumnCountNewlines() {
        String in = "[1,\n 2,\n x]";
        PegParseException e = fail(PegGrammarsTest.JSONISH, in);
        assertEquals(3, e.line());
        assertEquals(in.indexOf('x') - in.lastIndexOf('\n'), e.column());
    }

    @Test
    void failuresInsidePositivePredicateAreSilent() {
        // &'zz' fails at 0 but only the outer expectation ("&'zz'") is reported, not 'zz' as a terminal
        PegParseException e = fail("S <- &'zz' 'a'", "ab");
        assertEquals(List.of("&'zz'"), List.copyOf(e.expected()));
        assertEquals(0, e.position());
    }

    @Test
    void negativePredicateRecordsNot() {
        PegParseException e = fail("S <- !'a' .", "a");
        assertEquals(List.of("not 'a'"), List.copyOf(e.expected()));
    }

    @Test
    void successfulPartialMatchReportsEndOfInputWhenNothingFailedFurther() {
        // 'ab' fully matched; extra 'c' : no terminal failed at index 2 other than via the end-of-grammar
        PegParseException e = fail("S <- 'ab'", "abc");
        assertEquals(2, e.position());
        assertEquals(List.of("end of input"), List.copyOf(e.expected()));
        assertEquals("line 1, column 3: expected end of input, found 'c'", e.getMessage());
    }

    @Test
    void emptyGrammarMatchFailureAtStart() {
        PegParseException e = fail("S <- 'x'", "");
        assertEquals(0, e.position());
        assertEquals("line 1, column 1: expected 'x', found end of input", e.getMessage());
    }
}
