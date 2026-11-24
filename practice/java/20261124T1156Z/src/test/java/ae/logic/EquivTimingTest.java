package ae.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EquivTimingTest {
    private static Netlist p(String s) { return NetlistParser.parse(s); }

    @Test
    void rippleAndLookaheadAddersAreEquivalentUpToEightBits() {
        for (int n = 1; n <= 8; n++)
            assertEquals(Optional.empty(), Equiv.check(p(Circuits.ripple(n)), p(Circuits.lookahead(n))), "bits " + n);
    }

    @Test
    void droppedCarryTermGivesTheMinimalCounterexample() {
        String good = Circuits.ripple(4);
        // t1 = AND p1 c1 is the propagate term of carry 2; make it constant false
        String bad = good.replace("t1 = AND p1 c1", "t1 = AND p1 g1");
        assertNotEquals(good, bad);
        Netlist a = p(good), b = p(bad);
        Optional<Map<String, Boolean>> cex = Equiv.check(a, b);
        assertTrue(cex.isPresent());
        // input order a0 a1 a2 a3 b0 b1 b2 b3 cin (a0 = LSB). Smallest failing vector: a0=1, b0=1 (generate bit 0),
        // a1=1 (propagate bit 1): index 1 + 2 + 16 = 19
        Map<String, Boolean> expected = new HashMap<>();
        for (String in : a.inputs()) expected.put(in, in.equals("a0") || in.equals("b0") || in.equals("a1"));
        assertEquals(expected, cex.get());
        assertNotEquals(a.eval(cex.get()), b.eval(cex.get()));
        // nothing smaller fails
        for (int v = 0; v < 19; v++) {
            Map<String, Boolean> in = new HashMap<>();
            for (int i = 0; i < a.inputs().size(); i++) in.put(a.inputs().get(i), ((v >> i) & 1) == 1);
            assertEquals(a.eval(in), b.eval(in), "vector " + v);
        }
    }

    @Test
    void counterexampleAtVectorZeroAndAtTheLastVector() {
        Netlist x = p("input a b\noutput y\ny = XNOR a b\n");
        Netlist z = p("input a b\noutput y\ny = XOR a b\n");
        assertEquals(Map.of("a", false, "b", false), Equiv.check(x, z).orElseThrow());
        Netlist and = p("input a b\noutput y\ny = AND a b\n");
        Netlist and0 = p("input a b\noutput y\nm = NAND a b\ny = NOT m\n");
        assertTrue(Equiv.check(and, and0).isEmpty());
        Netlist onlyAtEnd = p("input a b c d e f g h\noutput y\ny = AND a b c d e f g h @1\n");
        Netlist never = p("input a b c d e f g h\noutput y\nk = CONST0\ny = BUF k\n");
        Map<String, Boolean> all = new HashMap<>();
        for (String s : onlyAtEnd.inputs()) all.put(s, true);
        assertEquals(all, Equiv.check(onlyAtEnd, never).orElseThrow(), "differs only at vector 255");
    }

    @Test
    void checksSevenAndSixtyFourVectorBoundaries() {
        // 0 inputs: one vector. 6 inputs: exactly one 64-vector word. 7 inputs: two words, differing only in word 2.
        assertTrue(Equiv.check(p("output y\ny = CONST1\n"), p("output y\nk = CONST1\ny = BUF k\n")).isEmpty());
        assertEquals(Map.of(), Equiv.check(p("output y\ny = CONST1\n"), p("output y\ny = CONST0\n")).orElseThrow());
        String six = "input a b c d e f\noutput y\ny = XOR a b c d e f\n";
        assertTrue(Equiv.check(p(six), p(six.replace("XOR", "XNOR").replace("y = XNOR", "m = XNOR") + "y = NOT m\n")).isEmpty());
        Netlist seven = p("input a b c d e f g\noutput y\ny = CONST0\n");
        Netlist sevenBug = p("input a b c d e f g\noutput y\nt = AND a b c d e f g\ny = BUF t\n");
        Map<String, Boolean> allTrue = new HashMap<>();
        for (String s : seven.inputs()) allTrue.put(s, true);
        assertEquals(allTrue, Equiv.check(seven, sevenBug).orElseThrow());
        Netlist sevenBug2 = p("input a b c d e f g\noutput y\nn = NOT f\nt = AND g n\ny = BUF t\n");
        // g=1, f=0 first at vector 64 (g = bit 6)
        Map<String, Boolean> only = new HashMap<>();
        for (String s : seven.inputs()) only.put(s, s.equals("g"));
        assertEquals(only, Equiv.check(seven, sevenBug2).orElseThrow());
    }

    @Test
    void matchesNamesNotPositions() {
        // o3 is asymmetric (x AND NOT y): a swapped input mapping would be caught
        Netlist a = p("input x y\noutput o1 o2 o3\nny = NOT y\no1 = AND x y\no2 = OR x y\no3 = AND x ny\n");
        Netlist b = p("input y x\noutput o3 o2 o1\nnn = NOT y\no2 = OR x y\no1 = AND y x\no3 = AND x nn\n");
        assertTrue(Equiv.check(a, b).isEmpty());
        // x AND NOT x is constant false; a's o3 is true at x=1,y=0, which is vector 1 (x = LSB)
        Netlist wrong = p("input y x\noutput o3 o2 o1\nnn = NOT x\no2 = OR x y\no1 = AND y x\no3 = AND x nn\n");
        assertEquals(Map.of("x", true, "y", false), Equiv.check(a, wrong).orElseThrow());
    }

    @Test
    void rejectsIncomparableNetlists() {
        Netlist a = p("input x y\noutput o\no = AND x y\n");
        assertThrows(IllegalArgumentException.class, () -> Equiv.check(a, p("input x z\noutput o\no = AND x z\n")));
        assertThrows(IllegalArgumentException.class, () -> Equiv.check(a, p("input x y\noutput q\nq = AND x y\n")));
        assertThrows(IllegalArgumentException.class,
            () -> Equiv.check(a, p("input x y\noutput o\nd = AND x y\no = DFF d\n")));
        StringBuilder wide = new StringBuilder("input");
        for (int i = 0; i < 21; i++) wide.append(" i").append(i);
        wide.append("\noutput o\no = BUF i0\n");
        Netlist w = p(wide.toString());
        assertThrows(IllegalArgumentException.class, () -> Equiv.check(w, w));
    }

    @Test
    void twentyInputsAreAccepted() {
        StringBuilder sb = new StringBuilder("input");
        for (int i = 0; i < 20; i++) sb.append(" i").append(i);
        sb.append("\noutput o\no = XOR");
        for (int i = 0; i < 20; i++) sb.append(" i").append(i);
        Netlist parity = p(sb.append('\n').toString());
        assertTrue(Equiv.check(parity, parity).isEmpty());
    }

    @Test
    void randomCircuitsAreEquivalentToThemselvesAndDifferentFromMutations() {
        Random r = new Random(99);
        int differing = 0;
        for (int c = 0; c < 100; c++) {
            String text = Circuits.random(r, 2 + r.nextInt(5), 5 + r.nextInt(15), 0);
            Netlist a = p(text);
            assertTrue(Equiv.check(a, p(text.replaceAll("@\\d", "@0"))).isEmpty(), "delays never change the function");
            // Flip one gate's op and compare against an exhaustive scalar oracle
            String[] lines = text.split("\n");
            int k = 0;
            for (int i = 0; i < lines.length; i++) if (lines[i].startsWith("g") && lines[i].contains(" = AND ")) { k = i; break; }
            if (k == 0) continue;
            lines[k] = lines[k].replace(" = AND ", " = OR ");
            Netlist b = p(String.join("\n", lines) + "\n");
            Optional<Map<String, Boolean>> cex = Equiv.check(a, b);
            boolean oracleDiffers = false;
            int nin = a.inputs().size();
            for (int v = 0; v < (1 << nin) && !oracleDiffers; v++) {
                Map<String, Boolean> in = new HashMap<>();
                for (int i = 0; i < nin; i++) in.put(a.inputs().get(i), ((v >> i) & 1) == 1);
                oracleDiffers = !a.eval(in).equals(b.eval(in));
            }
            assertEquals(oracleDiffers, cex.isPresent(), "circuit " + c);
            if (cex.isPresent()) { differing++; assertNotEquals(a.eval(cex.get()), b.eval(cex.get())); }
        }
        assertTrue(differing > 10, "mutations that changed behaviour: " + differing);
    }

    // ---- timing ----

    @Test
    void rippleCarryCriticalPathIsTwoNPlusOne() {
        Timing.Path path = Timing.criticalPath(p(Circuits.ripple(4)));
        assertEquals(9, path.delay());
        assertEquals(List.of("a0", "p0", "t0", "c1", "t1", "c2", "t2", "c3", "t3", "cout"), path.signals());
    }

    @Test
    void lookaheadIsShallowerThanRipple() {
        int cla = Timing.criticalPath(p(Circuits.lookahead(8))).delay();
        int rc = Timing.criticalPath(p(Circuits.ripple(8))).delay();
        assertEquals(17, rc);
        assertEquals(4, cla);   // p/g (1) -> AND (2) -> OR (3) -> sum XOR (4)
    }

    @Test
    void delaysAndDffOutputsContributeToArrival() {
        Netlist n = p("input a\noutput z\nq = DFF a @4\nm = BUF q @2\nz = AND m a @5\n");
        Timing.Path path = Timing.criticalPath(n);
        assertEquals(11, path.delay());
        assertEquals(List.of("q", "m", "z"), path.signals(), "DFF Q is a source arriving at its clock-to-q");
    }

    @Test
    void inputsOnlyNetlistHasZeroDelayPathOfFirstInput() {
        Timing.Path path = Timing.criticalPath(p("input a b\n"));
        assertEquals(0, path.delay());
        assertEquals(List.of("a"), path.signals());
        assertEquals(new Timing.Path(0, List.of()), Timing.criticalPath(p("")));
    }

    @Test
    void pathsOfRandomCircuitsAreStructurallyValid() {
        Random r = new Random(3);
        for (int c = 0; c < 100; c++) {
            Netlist n = p(Circuits.random(r, 3, 4 + r.nextInt(25), r.nextInt(3)));
            Timing.Path path = Timing.criticalPath(n);
            Map<String, Netlist.Gate> byName = new HashMap<>();
            for (Netlist.Gate g : n.gates()) byName.put(g.name(), g);
            List<String> s = new ArrayList<>(path.signals());
            Netlist.Gate first = byName.get(s.get(0));
            assertTrue(first == null || first.op() == Op.DFF || first.ins().isEmpty(), "source: " + s.get(0));
            int sum = first == null ? 0 : first.delay();
            for (int i = 1; i < s.size(); i++) {
                Netlist.Gate g = byName.get(s.get(i));
                assertNotNull(g);
                assertTrue(g.ins().contains(s.get(i - 1)), s.get(i - 1) + " feeds " + g.name());
                sum += g.delay();
            }
            assertEquals(sum, path.delay(), "circuit " + c + " " + s);
        }
    }
}
