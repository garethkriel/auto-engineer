package ae.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EvalTest {
    /** Truth tables as literals: character v is the output for vector v (input k = bit k of v). */
    @ParameterizedTest
    @CsvSource({
        "AND,2,0001", "OR,2,0111", "XOR,2,0110", "NAND,2,1110", "NOR,2,1000", "XNOR,2,1001",
        "AND,3,00000001", "OR,3,01111111", "XOR,3,01101001", "NAND,3,11111110", "NOR,3,10000000", "XNOR,3,10010110",
        "NOT,1,10", "BUF,1,01",
    })
    void truthTables(String op, int arity, String table) {
        StringBuilder sb = new StringBuilder("input");
        for (int i = 0; i < arity; i++) sb.append(" x").append(i);
        sb.append("\noutput y\ny = ").append(op);
        for (int i = 0; i < arity; i++) sb.append(" x").append(i);
        Netlist n = NetlistParser.parse(sb.toString());
        assertEquals(1 << arity, table.length());
        StringBuilder scalar = new StringBuilder();
        for (int v = 0; v < (1 << arity); v++) {
            Map<String, Boolean> in = new HashMap<>();
            for (int i = 0; i < arity; i++) in.put("x" + i, ((v >> i) & 1) == 1);
            scalar.append(n.eval(in).get("y") ? '1' : '0');
        }
        assertEquals(table, scalar.toString());
        long[] words = new long[arity];
        for (int v = 0; v < (1 << arity); v++)
            for (int i = 0; i < arity; i++) if (((v >> i) & 1) == 1) words[i] |= 1L << v;
        long out = n.evalPacked(words)[0];
        StringBuilder packed = new StringBuilder();
        for (int v = 0; v < (1 << arity); v++) packed.append(((out >> v) & 1) == 1 ? '1' : '0');
        assertEquals(table, packed.toString());
    }

    @Test
    void muxSelectsSecondOperandWhenSelIsOne() {
        // inputs s,a,b: vector bit0 = s, bit1 = a, bit2 = b; out = s ? b : a
        Netlist n = NetlistParser.parse("input s a b\noutput m\nm = MUX s a b\n");
        String expected = "00100111";
        StringBuilder got = new StringBuilder();
        for (int v = 0; v < 8; v++) {
            Map<String, Boolean> in = Map.of("s", (v & 1) == 1, "a", (v & 2) != 0, "b", (v & 4) != 0);
            got.append(n.eval(in).get("m") ? '1' : '0');
        }
        assertEquals(expected, got.toString());
    }

    @Test
    void constantsAndMissingInputsDefaultToFalse() {
        Netlist n = NetlistParser.parse("input a\noutput one zero na\none = CONST1\nzero = CONST0\nna = NOT a\n");
        Map<String, Boolean> out = n.eval(Map.of());
        assertEquals(List.of("one", "zero", "na"), List.copyOf(out.keySet()), "outputs keep declaration order");
        assertEquals(List.of(true, false, true), List.copyOf(out.values()));
    }

    @Test
    void unknownInputOrStateNameIsRejected() {
        Netlist n = NetlistParser.parse("input a\noutput q\nq = DFF a\n");
        assertThrows(IllegalArgumentException.class, () -> n.eval(Map.of("zz", true)));
        assertThrows(IllegalArgumentException.class, () -> n.eval(Map.of(), Map.of("a", true)));
        assertTrue(n.eval(Map.of(), Map.of("q", true)).get("q"));
        assertFalse(n.eval(Map.of("a", true)).get("q"), "state defaults to false; D is not visible at Q");
    }

    @Test
    void evalPackedRejectsSequentialAndWrongWidth() {
        Netlist seq = NetlistParser.parse("input a\noutput q\nq = DFF a\n");
        assertThrows(IllegalStateException.class, () -> seq.evalPacked(new long[1]));
        Netlist comb = NetlistParser.parse("input a b\noutput x\nx = AND a b\n");
        assertThrows(IllegalArgumentException.class, () -> comb.evalPacked(new long[1]));
    }

    private static int toInt(Map<String, Boolean> out, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) if (out.get("s" + i)) v |= 1 << i;
        if (out.get("cout")) v |= 1 << n;
        return v;
    }

    private static Map<String, Boolean> adderInputs(int n, int a, int b, int cin) {
        Map<String, Boolean> in = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) in.put("a" + i, ((a >> i) & 1) == 1);
        for (int i = 0; i < n; i++) in.put("b" + i, ((b >> i) & 1) == 1);
        in.put("cin", cin == 1);
        return in;
    }

    @Test
    void fourBitAddersMatchIntegerAdditionExhaustively() {
        Netlist rc = NetlistParser.parse(Circuits.ripple(4));
        Netlist cla = NetlistParser.parse(Circuits.lookahead(4));
        for (int a = 0; a < 16; a++)
            for (int b = 0; b < 16; b++)
                for (int cin = 0; cin < 2; cin++) {
                    Map<String, Boolean> in = adderInputs(4, a, b, cin);
                    assertEquals(a + b + cin, toInt(rc.eval(in), 4), "ripple " + a + "+" + b + "+" + cin);
                    assertEquals(a + b + cin, toInt(cla.eval(in), 4), "lookahead " + a + "+" + b + "+" + cin);
                }
    }

    @Test
    void sixteenBitAddersMatchIntegerAdditionOnRandomOperands() {
        Netlist rc = NetlistParser.parse(Circuits.ripple(16));
        Netlist cla = NetlistParser.parse(Circuits.lookahead(16));
        Random r = new Random(7);
        for (int t = 0; t < 300; t++) {
            int a = r.nextInt(1 << 16), b = r.nextInt(1 << 16), cin = r.nextInt(2);
            if (t == 0) { a = 0xFFFF; b = 0; cin = 1; }   // longest carry chain
            if (t == 1) { a = 0xFFFF; b = 0xFFFF; cin = 1; }
            Map<String, Boolean> in = adderInputs(16, a, b, cin);
            assertEquals(a + b + cin, toInt(rc.eval(in), 16));
            assertEquals(a + b + cin, toInt(cla.eval(in), 16));
        }
    }

    @Test
    void packedMatchesScalarOnRandomCircuits() {
        Random r = new Random(11);
        for (int c = 0; c < 150; c++) {
            int nin = 1 + r.nextInt(7);
            Netlist n = NetlistParser.parse(Circuits.random(r, nin, 1 + r.nextInt(25), 0));
            long[] words = new long[nin];
            for (int i = 0; i < nin; i++) words[i] = r.nextLong();
            long[] out = n.evalPacked(words);
            assertEquals(n.outputs().size(), out.length);
            for (int k = 0; k < 64; k++) {
                Map<String, Boolean> in = new HashMap<>();
                for (int i = 0; i < nin; i++) in.put("i" + i, ((words[i] >>> k) & 1) == 1);
                Map<String, Boolean> expected = n.eval(in);
                for (int o = 0; o < out.length; o++)
                    assertEquals(expected.get(n.outputs().get(o)), ((out[o] >>> k) & 1) == 1,
                        "circuit " + c + " vector " + k + " output " + n.outputs().get(o));
            }
        }
    }
}
