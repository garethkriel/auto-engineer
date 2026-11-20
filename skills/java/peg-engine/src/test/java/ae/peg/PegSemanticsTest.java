package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Table of (grammar, input) -> length of matched prefix, -1 = no match. Expected values are hand-derived. */
class PegSemanticsTest {
    @ParameterizedTest(name = "{0} on [{1}] -> {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        // ordered choice commits to the first success: 'a' wins over 'ab'
        "S <- 'a' / 'ab' | ab | 1",
        "S <- 'ab' / 'a' | ab | 2",
        // choice alternatives are retried only when the earlier one FAILS
        "S <- 'a' 'b' / 'a' 'c' | ac | 2",
        // repetition and ? are possessive: a*a and a?a on 'a' can never match
        "S <- 'a'* 'a' | aaa | -1",
        "S <- 'a'* 'b' | aaab | 4",
        "S <- 'a'+ | b | -1",
        "S <- 'a'? 'a' | a | -1",
        "S <- 'a'? 'a' | aa | 2",
        // predicates consume nothing
        "S <- &'ab' 'a' | ab | 1",
        "S <- &'ab' 'a' | ac | -1",
        "S <- !'ab' 'a' | ac | 1",
        "S <- !'ab' 'a' | ab | -1",
        "S <- !. | <empty> | 0",
        "S <- !. | x | -1",
        // classic: a^n b^n c^n with predicates
        "S <- &(A 'c') 'a'* B !. ; A <- 'a' A? 'b' ; B <- 'b' B? 'c' | aabbcc | 6",
        "S <- &(A 'c') 'a'* B !. ; A <- 'a' A? 'b' ; B <- 'b' B? 'c' | aabbbcc | -1",
        "S <- &(A 'c') 'a'* B !. ; A <- 'a' A? 'b' ; B <- 'b' B? 'c' | aabcc | -1",
        // empty literal always matches; empty input
        "S <- '' | xyz | 0",
        "S <- 'a'* | <empty> | 0",
        "S <- . | <empty> | -1",
        // classes
        "S <- [a-c]+ | abcd | 3",
        "S <- [^a-c]+ | xyzab | 3",
        "S <- [a-cx] [a-cx] | xb | 2",
        "S <- [-a] | - | 1",
        // string comparison is case sensitive
        "S <- 'Ab' | ab | -1",
        // rule reference and recursion on the right
        "S <- '(' S ')' / 'x' | ((x)) | 5",
        "S <- '(' S ')' / 'x' | ((x) | -1",
    })
    void prefixLength(String grammar, String input, int expected) {
        Grammar g = Grammar.parse(grammar.replace(" ; ", "\n"));
        for (boolean memo : new boolean[] {true, false}) {
            Optional<Node> n = new PegParser(g, memo).matchPrefix(input.equals("<empty>") ? "" : input);
            assertEquals(expected, n.map(Node::end).orElse(-1), grammar + " on " + input + " memo=" + memo);
        }
    }
}
