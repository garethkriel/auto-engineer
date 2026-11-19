package ae.hm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HmTest {

    @ParameterizedTest(name = "{0} : {1}")
    @CsvSource(delimiter = '|', value = {
        "42 | Int",
        "true | Bool",
        "\\x -> x | a -> a",
        "\\x y -> x | a -> b -> a",
        "\\x y -> y | a -> b -> b",
        "\\f g x -> f (g x) | (a -> b) -> (c -> a) -> c -> b",
        "\\x y z -> x z (y z) | (a -> b -> c) -> (a -> b) -> a -> c",
        "\\f x y -> f y x | (a -> b -> c) -> b -> a -> c",
        "\\f -> \\x -> f (f x) | (a -> a) -> a -> a",
        "\\x -> x + 1 | Int -> Int",
        "\\x -> (x, x) | a -> (a, a)",
        "\\p -> fst p | (a, b) -> a",
        "\\x -> cons x nil | a -> [a]",
        "nil | [a]",
        "cons 1 nil | [Int]",
        "\\f -> \\x -> f x == 3 | (a -> Int) -> a -> Bool",
        "if true then 1 else 2 | Int",
        "\\c -> if c then (\\x -> x) else (\\y -> y) | Bool -> a -> a",
        "(\\x -> x) 5 | Int",
        "1 + 2 * 3 - 4 | Int",
        "\\g -> g (g 1) | (Int -> Int) -> Int",
    })
    void knownPrincipalTypes(String src, String expected) {
        assertEquals(expected, Hm.typeOf(src), src);
    }

    @Test
    void arrowsAssociateToTheRightAndParenthesiseLeftOperands() {
        assertEquals("(a -> b) -> a -> b", Hm.typeOf("\\f x -> f x"));
        assertEquals("a -> b -> a", Hm.typeOf("\\x y -> x"));
        assertEquals("[a -> a]", Hm.typeOf("cons (\\x -> x) nil"));
        assertEquals("([a], Int -> Bool)", Hm.typeOf("(nil, \\n -> n == 0)"));
    }

    @Test
    void variableNamesFollowFirstAppearanceInTheShownType() {
        // inference creates the variables in a different order than they are shown
        assertEquals("a -> b -> (b, a)", Hm.typeOf("\\x y -> (y, x)"));
        // 27 distinct variables run past 'z': the 27th is a1, nil's element variable is b1
        StringBuilder src = new StringBuilder("\\");
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 27; i++) {
            src.append("v").append(i).append(' ');
            expected.append(i < 26 ? String.valueOf((char) ('a' + i)) : "a1").append(" -> ");
        }
        src.append("-> nil");
        expected.append("[b1]");
        assertEquals(expected.toString(), Hm.typeOf(src.toString()));
    }

    @Test
    void letPolymorphismAllowsTwoInstantiations() {
        assertEquals("(Int, Bool)", Hm.typeOf("let id = \\x -> x in (id 1, id true)"));
        assertEquals("(Int, Bool)", Hm.typeOf("let k x y = x in (k 1 true, k true 2)")); 
        assertEquals("([Int], [Bool])",
            Hm.typeOf("let single x = cons x nil in (single 1, single true)"));
        // nested generalisation: the inner let is generalised on its own
        assertEquals("(Int, Bool)",
            Hm.typeOf("let f = \\a -> let g = \\b -> b in g a in (f 1, f true)"));
    }

    @Test
    void lambdaBoundVariablesAreMonomorphic() {
        TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf("\\f -> (f 1, f true)"));
        assertEquals(TypeException.Kind.MISMATCH, e.kind());
        assertEquals(1, e.line());
        assertEquals(13, e.col(), e.getMessage()); // the application `f true` starts at col 13
    }

    @Test
    void letBoundFunctionIsNotGeneralisedOverOuterLambdaVariables() {
        // f's type is  b -> (b, a)  with a shared with y, so only b is generalised
        assertEquals("a -> ((Int, a), (Bool, a))",
            Hm.typeOf("\\y -> let f = \\x -> (x, y) in (f 1, f true)"));
    }

    @Test
    void outerVariableCannotBeUsedAtTwoTypesThroughALet() {
        TypeException e = assertThrows(TypeException.class,
            () -> Hm.typeOf("\\y -> let f = \\x -> y in (f 1) + (if f 2 then 1 else 0)"));
        assertEquals(TypeException.Kind.MISMATCH, e.kind());
    }

    @Test
    void recursionIsMonomorphicInsideItsOwnDefinition() {
        assertEquals("Int -> Int",
            Hm.typeOf("let rec fact = \\n -> if n == 0 then 1 else n * fact (n - 1) in fact"));
        assertEquals("[a] -> Int",
            Hm.typeOf("let rec len l = if isNil l then 0 else 1 + len (tail l) in len"));
        assertEquals("(a -> b) -> [a] -> [b]",
            Hm.typeOf("let rec map f l = if isNil l then nil else cons (f (head l)) (map f (tail l)) in map"));
        // after the definition the recursive function is generalised
        assertEquals("(Int, Int)",
            Hm.typeOf("let rec len l = if isNil l then 0 else 1 + len (tail l) in (len (cons 1 nil), len (cons true nil))"));
    }

    @Test
    void polymorphicRecursionIsRejectedBecauseRecIsMonomorphic() {
        // f used at [a] and at a inside its own body
        TypeException e = assertThrows(TypeException.class,
            () -> Hm.typeOf("let rec f x = if true then 0 else f (cons x nil) in f"));
        assertEquals(TypeException.Kind.OCCURS, e.kind());
    }

    @Test
    void shadowingBuiltinsAndVariables() {
        assertEquals("Bool", Hm.typeOf("let not = \\x -> 1 in not 1 == 1"));
        assertEquals("a -> b -> b", Hm.typeOf("\\x -> let x = 1 in \\x -> x"));
        assertEquals("a -> Int", Hm.typeOf("\\x -> let x = 1 in x"));
    }

    @Test
    void occursCheckReportsTheInfiniteType() {
        TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf("\\x -> x x"));
        assertEquals(TypeException.Kind.OCCURS, e.kind());
        assertEquals(1, e.line());
        assertEquals(7, e.col());
        assertEquals("occurs check: a occurs in a -> b", e.getMessage());
    }

    @Test
    void mismatchesCarryPositionAndBothTypes() {
        TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf("true + 1"));
        assertEquals(TypeException.Kind.MISMATCH, e.kind());
        assertEquals(6, e.col()); // operator token
        assertEquals("cannot unify Int with Bool", e.getMessage());

        e = assertThrows(TypeException.class, () -> Hm.typeOf("if 1 then 2 else 3"));
        assertEquals(4, e.col());
        assertEquals("cannot unify Int with Bool", e.getMessage());

        e = assertThrows(TypeException.class, () -> Hm.typeOf("if true then 2 else false"));
        assertEquals(1, e.line());
        assertEquals(21, e.col());
        assertEquals("cannot unify Int with Bool", e.getMessage());

        e = assertThrows(TypeException.class, () -> Hm.typeOf("fst 1"));
        assertEquals(1, e.col());
        assertEquals("cannot unify (a, b) with Int", e.getMessage());

        e = assertThrows(TypeException.class, () -> Hm.typeOf("cons 1 (cons true nil)"));
        assertEquals(TypeException.Kind.MISMATCH, e.kind());
    }

    @Test
    void positionsSpanLines() {
        String src = "let f = \\x ->\n  x + 1\nin\n  f true";
        TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf(src));
        assertEquals(TypeException.Kind.MISMATCH, e.kind());
        assertEquals(4, e.line());
        assertEquals(3, e.col()); // the application `f true`
    }

    @Test
    void unboundVariablesAreReportedAtTheirOccurrence() {
        TypeException e = assertThrows(TypeException.class, () -> Hm.typeOf("\\x -> x + y"));
        assertEquals(TypeException.Kind.UNBOUND, e.kind());
        assertEquals(1, e.line());
        assertEquals(11, e.col());
        assertEquals("unbound variable y", e.getMessage());
        // let is not recursive without 'rec'
        e = assertThrows(TypeException.class, () -> Hm.typeOf("let f = \\x -> f x in f"));
        assertEquals(TypeException.Kind.UNBOUND, e.kind());
        assertEquals(15, e.col());
    }

    @Test
    void failedInferenceThrowsInsteadOfReturningAPartialType() {
        assertThrows(TypeException.class, () -> Hm.typeOf("(\\x -> x + 1) true"));
        // every call is independent: no state leaks from a failure
        assertEquals("a -> a", Hm.typeOf("\\x -> x"));
    }

    @Test
    void parseErrors() {
        ParseException e = assertThrows(ParseException.class, () -> Hm.typeOf("\\x x"));
        assertEquals(1, e.line());
        assertEquals(5, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("(1 + 2"));
        assertEquals(7, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("1 +\n"));
        assertEquals(2, e.line());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("let in = 1 in 2"));
        assertEquals(5, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("1 $ 2"));
        assertEquals(3, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("1 2 )"));
        assertEquals(5, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf(""));
        assertEquals(1, e.col());
        e = assertThrows(ParseException.class, () -> Hm.typeOf("99999999999"));
        assertEquals(1, e.col());
    }

    @Test
    void nullSourceIsRejected() {
        assertThrows(NullPointerException.class, () -> Hm.typeOf(null));
    }

    @Test
    void deepNestingDoesNotOverflowForReasonableInputs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("let x").append(i).append(" = ").append(i == 0 ? "1" : "x" + (i - 1)).append(" in ");
        }
        sb.append("x299");
        assertEquals("Int", Hm.typeOf(sb.toString()));
        // long application chain: f applied to 50 integer literals
        StringBuilder chain = new StringBuilder("\\f -> f");
        StringBuilder expected = new StringBuilder("(");
        for (int i = 0; i < 50; i++) {
            chain.append(" 1");
            expected.append("Int -> ");
        }
        expected.append("a) -> a");
        assertEquals(expected.toString(), Hm.typeOf(chain.toString()));
    }
}
