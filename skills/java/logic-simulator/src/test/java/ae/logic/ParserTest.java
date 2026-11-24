package ae.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ParserTest {
    private static NetlistException fail(String text) {
        return assertThrows(NetlistException.class, () -> NetlistParser.parse(text), text);
    }

    @Test
    void parsesFieldsDefaultsAndComments() {
        Netlist n = NetlistParser.parse("""
            # a comment line
            input a b   # trailing comment
            output x q
            x = XOR a b @2
            q = DFF x
            k = CONST1
            """);
        assertEquals(List.of("a", "b"), n.inputs());
        assertEquals(List.of("x", "q"), n.outputs());
        assertEquals(3, n.gates().size());
        Netlist.Gate x = n.gates().get(0);
        assertEquals(new Netlist.Gate("x", Op.XOR, List.of("a", "b"), 2, 4), x);
        assertEquals(1, n.gates().get(1).delay(), "DFF default clock-to-q is 1");
        assertEquals(0, n.gates().get(2).delay(), "CONST default delay is 0");
        assertFalse(n.isCombinational());
    }

    @Test
    void forwardReferencesAndDffFeedbackAreAccepted() {
        Netlist n = NetlistParser.parse("q = DFF nq\nnq = NOT q\noutput q\n");
        assertEquals(List.of("q", "nq"), n.gates().stream().map(Netlist.Gate::name).toList());
        assertEquals(0, n.inputs().size());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "input a\\nx = AND a|2|AND needs at least 2",
        "input a\\nx = FOO a a|2|unknown op FOO",
        "input a b\\nx = NOT a b|2|NOT takes exactly 1",
        "input a b\\nx = MUX a b|2|MUX takes exactly 3",
        "input a\\nx = DFF a a|2|DFF takes exactly 1",
        "input a\\nk = CONST1 a|2|CONST1 takes no operands",
        "input a b\\nx = AND a b @-1|2|bad delay",
        "input a b\\nx = AND a b @x|2|bad delay",
        "input a b\\nx = AND a b @1001|2|bad delay",
        "input a b\\nx = AND a b @2 b|2|delay must be last",
        "input a\\nx AND a a|2|missing equals sign",
        "input a\\nx-1 = NOT a|2|bad name x-1",
        "input a\\nx = |2|missing op",
        "input|1|input needs at least one name",
        "input a a|1|duplicate name a",
        "input a\\na = NOT a|2|duplicate name a",
        "input a\\nx = NOT a\\nx = BUF a|3|duplicate name x",
        "input a\\nx = AND a zz|2|unknown signal zz",
        "input a\\noutput nope|2|unknown output nope",
        "input a\\noutput a a|2|duplicate output a",
        "input a\\nAND = NOT a|2|bad name AND",
        "input input|1|bad name input",
        "input a\\nx = NOT a\\ny = AND x w|3|unknown signal w",
        "input a\\nx = AND a y\\ny = NOT x\\nz = BUF a|2|combinational loop: x -> y -> x",
        "input a\\nx = NOT x|2|combinational loop: x -> x",
        "input a\\nb1 = NOT c1\\nc1 = NOT d1\\nd1 = AND a b1|2|combinational loop: b1 -> c1 -> d1 -> b1",
    })
    void rejectsWithLineAndMessage(String text, int line, String message) {
        NetlistException e = fail(text.replace("\\n", "\n"));
        assertEquals(line, e.line(), text);
        assertTrue(e.getMessage().contains(message), () -> text + " -> " + e.getMessage());
    }

    @Test
    void errorMessageStartsWithLineNumber() {
        NetlistException e = fail("input a\nx = FOO a a\n");
        assertTrue(e.getMessage().startsWith("line 2: "), e.getMessage());
    }

    @Test
    void dffBreaksLoopsButOnlyThroughTheDffOperand() {
        // loop closes through the DFF: fine. The same loop without DFF: rejected.
        NetlistParser.parse("input a\nx = AND a q\nq = DFF x\n");
        NetlistException e = fail("input a\nx = AND a q\nq = BUF x\n");
        assertTrue(e.getMessage().contains("combinational loop: x -> q -> x"), e.getMessage());
    }

    @Test
    void generatedCircuitsAreValid() {
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            Netlist n = NetlistParser.parse(Circuits.random(r, 2 + r.nextInt(6), 1 + r.nextInt(30), r.nextInt(4)));
            assertFalse(n.outputs().isEmpty());
        }
        for (int bits = 1; bits <= 8; bits++) {
            NetlistParser.parse(Circuits.ripple(bits));
            NetlistParser.parse(Circuits.lookahead(bits));
        }
    }
}
