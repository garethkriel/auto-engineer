package ae.shellx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WordExpanderTest {
    private static final Map<String, String> ENV = Map.of(
        "A", "x y", "B", "b", "E", "", "S", " p  q ", "N", "7");

    private static List<String> x(String line) {
        return WordExpander.expand(line, ENV);
    }

    @Test
    void firstTestPlainWords() {
        assertEquals(List.of("a", "b", "c"), x("a  b\tc "));
        assertEquals(List.of(), x("   "));
    }

    @Test
    void quotes() {
        assertEquals(List.of("a b", "c"), x("'a b' c"));
        assertEquals(List.of("a b$A"), x("\"a b\"'$A'"));
        assertEquals(List.of("\\n", "\"", "$B", "\\z"), x("\"\\n\" \"\\\"\" \"\\$B\" \"\\z\""));
        assertEquals(List.of("a b"), x("a\\ b"));
        assertEquals(List.of("a\\b", "a\\b"), x("\"a\\\\b\" \"a\\b\""));
        assertEquals(List.of("", ""), x("\"\" ''"));
        assertEquals(List.of("{a,b}"), x("'{a,b}'"));
    }

    @Test
    void parameterSplittingRules() {
        assertEquals(List.of("x", "y"), x("$A"));
        assertEquals(List.of("x y"), x("\"$A\""));
        assertEquals(List.of("1x", "y2"), x("1${A}2"));
        assertEquals(List.of("p", "q"), x("$S"));
        assertEquals(List.of(" p  q "), x("\"$S\""));
        assertEquals(List.of(), x("$E"));
        assertEquals(List.of(""), x("\"$E\""));
        assertEquals(List.of(), x("$UNSET"));
        assertEquals(List.of("b$"), x("$B$"));
        assertEquals(List.of("$", "$ "), x("$ \"$ \""));
    }

    @Test
    void operators() {
        assertEquals(List.of("d"), x("${E:-d}"));
        assertEquals(List.of(""), x("\"${E-d}\""));
        assertEquals(List.of("d"), x("${U-d}"));
        assertEquals(List.of("b"), x("${B:-d}"));
        assertEquals(List.of("alt"), x("${B:+alt}"));
        assertEquals(List.of(), x("${E:+alt}"));
        assertEquals(List.of("alt"), x("${E+alt}"));
        assertEquals(List.of("1", "7"), x("${#B} ${N}"));
        assertEquals(List.of("3"), x("${#A}"));
        assertEquals(List.of("0"), x("${#U}"));
        assertEquals(List.of("u", "v", "b"), x("${U:-u v} ${B}"));
        assertEquals(List.of("u v"), x("\"${U:-u v}\""));
        assertEquals(List.of("b"), x("${U:-${B}}"));
        assertEquals(List.of("a b"), x("${U:-'a b'}"));
    }

    @Test
    void braceLists() {
        assertEquals(List.of("a", "b", "c"), x("{a,b,c}"));
        assertEquals(List.of("pad", "pbd"), x("p{a,b}d"));
        assertEquals(List.of("a1", "a2", "b1", "b2"), x("{a,b}{1,2}"));
        assertEquals(List.of("a", "b", "c"), x("{a,{b,c}}"));
        // the empty alternative gives an empty unquoted word, which vanishes
        assertEquals(List.of("x"), x("{,x}"));
        assertEquals(List.of("", "x"), x("{\"\",x}"));
        assertEquals(List.of("{a}", "{a}"), x("{a} {a}"));
        // a closing brace before any comma is ordinary text, as in bash
        assertEquals(List.of("a}", "b"), x("{a},b}"));
        assertEquals(List.of("a}b", "c"), x("{a}b,c}"));
        assertEquals(List.of("{a}}"), x("{a}}"));
        assertEquals(List.of("{a,b"), x("{a,b"));
        assertEquals(List.of("{a,b", "{a,c"), x("{a,{b,c}"));
        assertEquals(List.of("{a}b", "{a}c"), x("{a}{b,c}"));
        assertEquals(List.of("{}"), x("{}"));
        assertEquals(List.of("a,b"), x("a,b"));
    }

    @Test
    void braceNotInQuotesOrEscapes() {
        assertEquals(List.of("{a,b}"), x("\"{a,b}\""));
        assertEquals(List.of("{a,b}"), x("\\{a,b}"));
        // an escaped comma is not a separator, so the group has no comma and stays literal
        assertEquals(List.of("{a,b}"), x("{a\\,b}"));
        assertEquals(List.of("{a,b}", "{a,c}"), x("{a\\,{b,c}}"));
    }

    @Test
    void braceSequences() {
        assertEquals(List.of("1", "2", "3"), x("{1..3}"));
        assertEquals(List.of("3", "2", "1"), x("{3..1}"));
        assertEquals(List.of("1", "5", "9"), x("{1..10..4}"));
        assertEquals(List.of("-1", "0", "1"), x("{-1..1}"));
        assertEquals(List.of("01", "02", "03"), x("{01..03}"));
        assertEquals(List.of("08", "09", "10"), x("{08..10}"));
        assertEquals(List.of("a1", "a2"), x("a{1..2}"));
        assertEquals(List.of("1", "5", "9"), x("{1..10..-4}"));
        assertEquals(List.of("01", "02", "03"), x("{1..03}"));
        assertEquals(List.of("{1..}"), x("{1..}"));
        assertEquals(List.of("{a..c}"), x("{a..c}"));
    }

    @Test
    void braceBeforeParameter() {
        // brace expansion happens first: {$B,c} -> $B c -> b c
        assertEquals(List.of("b", "c"), x("{$B,c}"));
        // ${...} is never brace-expanded, its default may contain braces
        assertEquals(List.of("{p,q}"), x("${U:-{p,q}}"));
        assertEquals(List.of("bX", "bY"), x("$B{X,Y}".replace("$B", "${B}")));
        // names are taken greedily AFTER brace expansion
        assertEquals(List.of(), x("$B{X,Y}"));
    }

    @Test
    void syntaxErrors() {
        assertEquals(0, assertThrows(ShellSyntaxException.class, () -> x("'abc")).position());
        assertEquals(3, assertThrows(ShellSyntaxException.class, () -> x("ab \"cd")).position());
        assertEquals(2, assertThrows(ShellSyntaxException.class, () -> x("ab\\")).position());
        assertEquals(1, assertThrows(ShellSyntaxException.class, () -> x("a${B")).position());
        assertEquals(0, assertThrows(ShellSyntaxException.class, () -> x("${1x}")).position());
        assertEquals(0, assertThrows(ShellSyntaxException.class, () -> x("${}")).position());
        assertEquals(0, assertThrows(ShellSyntaxException.class, () -> x("${B:=z}")).position());
        assertEquals(0, assertThrows(ShellSyntaxException.class, () -> x("${#B:-z}")).position());
        assertEquals(List.of("1"), x("${#B}"));
        // control: only the defect differs
        assertEquals(List.of("b"), x("${B}"));
        assertThrows(IllegalStateException.class, () -> x("{1..200}{1..200}"));
    }
}
