package ae.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SimulatorTest {
    private static Simulator.Change ch(long t, String s, boolean v) { return new Simulator.Change(t, s, v); }

    /** z = a AND NOT(a) with a slow inverter: a static-0 hazard. */
    private static final String HAZARD = "input a\noutput z\ny = NOT a @3\nz = AND a y @1\n";

    @Test
    void hazardProducesAGlitchOnRisingInput() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        assertTrue(sim.value("y"), "initial state is settled: y = NOT 0");
        assertFalse(sim.value("z"));
        sim.setInput("a", true);
        assertEquals(4, sim.run());
        // t=0 a rises; z sees (1,1) and rises at 1; y falls at 3; z falls at 4
        assertEquals(List.of(ch(0, "a", true), ch(1, "z", true), ch(3, "y", false), ch(4, "z", false)), sim.trace());
        assertEquals(4, sim.time());
        assertFalse(sim.value("z"));
    }

    @Test
    void fallingInputIsGlitchFree() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        sim.setInput("a", true);
        sim.run();
        sim.setInput("a", false);
        assertEquals(3, sim.run());
        // from t=4: a falls at 4; y rises at 7; z stays 0 throughout
        assertEquals(List.of(ch(0, "a", true), ch(1, "z", true), ch(3, "y", false), ch(4, "z", false),
            ch(4, "a", false), ch(7, "y", true)), sim.trace());
    }

    @Test
    void settingTheSameValueSchedulesNothing() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        sim.setInput("a", false);
        assertEquals(0, sim.run());
        assertTrue(sim.trace().isEmpty());
        assertEquals(0, sim.time());
    }

    @Test
    void settingAndRevertingBeforeRunLeavesNoTrace() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        sim.setInput("a", true);
        sim.setInput("a", false);
        assertEquals(0, sim.run());
        assertTrue(sim.trace().isEmpty());
    }

    @Test
    void zeroDelayChainResolvesInDeltaCyclesAtOneInstant() {
        Simulator sim = new Simulator(NetlistParser.parse(
            "input a\noutput c\nb = NOT a @0\nc = NOT b @0\n"));
        sim.setInput("a", true);
        assertEquals(0, sim.run());
        assertEquals(List.of(ch(0, "a", true), ch(0, "b", false), ch(0, "c", true)), sim.trace());
        assertTrue(sim.value("c"));
    }

    @Test
    void constantsAreSettledFromTheStart() {
        Simulator sim = new Simulator(NetlistParser.parse("input a\noutput x\nk = CONST1\nx = AND a k\n"));
        assertTrue(sim.value("k"));
        sim.setInput("a", true);
        sim.run();
        assertTrue(sim.value("x"));
    }

    @Test
    void dffCapturesAllDInputsAtTheSameInstant() {
        // two flops swap values: a shift ring of length 2 only works if both sample before either updates
        Simulator sim = new Simulator(NetlistParser.parse(
            "input load\noutput q0 q1\nq0 = DFF d0\nq1 = DFF q0\nd0 = OR load q1\n"));
        sim.setInput("load", true);
        sim.run();
        sim.clock();                       // q0 <- 1, q1 <- old q0 = 0
        assertTrue(sim.value("q0"));
        assertFalse(sim.value("q1"));
        sim.setInput("load", false);
        sim.run();
        sim.clock();                       // q0 <- q1 (0), q1 <- 1
        assertFalse(sim.value("q0"));
        assertTrue(sim.value("q1"));
        sim.clock();                       // q0 <- 1, q1 <- 0
        assertTrue(sim.value("q0"));
        assertFalse(sim.value("q1"));
    }

    @Test
    void dffOutputChangesAfterItsClockToQDelay() {
        Simulator sim = new Simulator(NetlistParser.parse(
            "input d\noutput q n\nq = DFF d @3\nn = NOT q @2\n"));
        sim.setInput("d", true);
        sim.run();                         // d visible, q not yet
        assertFalse(sim.value("q"));
        long t0 = sim.time();
        assertEquals(5, sim.clock());      // q at +3, n at +5
        assertEquals(List.of(ch(t0 + 3, "q", true), ch(t0 + 5, "n", false)),
            sim.trace().subList(sim.trace().size() - 2, sim.trace().size()));
    }

    @Test
    void fourBitCounterCountsModuloSixteen() {
        Netlist n = NetlistParser.parse("""
            output q0 q1 q2 q3
            q0 = DFF n0
            q1 = DFF n1
            q2 = DFF n2
            q3 = DFF n3
            n0 = NOT q0
            n1 = XOR q1 q0
            c1 = AND q0 q1
            n2 = XOR q2 c1
            c2 = AND c1 q2
            n3 = XOR q3 c2
            """);
        Simulator sim = new Simulator(n);
        for (int clock = 0; clock <= 40; clock++) {
            int v = 0;
            for (int i = 0; i < 4; i++) if (sim.value("q" + i)) v |= 1 << i;
            assertEquals(clock % 16, v, "after " + clock + " clocks");
            sim.clock();
        }
    }

    @Test
    void rejectsUnknownNames() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        assertThrows(IllegalArgumentException.class, () -> sim.setInput("y", true));  // a gate, not an input
        assertThrows(IllegalArgumentException.class, () -> sim.setInput("nope", true));
        assertThrows(IllegalArgumentException.class, () -> sim.value("nope"));
    }

    @Test
    void traceIsAnUnmodifiableSnapshot() {
        Simulator sim = new Simulator(NetlistParser.parse(HAZARD));
        sim.setInput("a", true);
        sim.run();
        List<Simulator.Change> t = sim.trace();
        assertThrows(UnsupportedOperationException.class, () -> t.add(ch(0, "a", true)));
        sim.setInput("a", false);
        sim.run();
        assertEquals(4, t.size(), "earlier snapshot unchanged");
        assertEquals(6, sim.trace().size());
    }

    /** Differential: event simulation vs levelised evaluation, with random stimuli, clocks and DFFs. */
    @Test
    void eventSimulationAgreesWithLevelisedEvaluationAndRespectsCriticalPath() {
        Random r = new Random(2026);
        int clocks = 0, inputRuns = 0, glitchy = 0;
        for (int c = 0; c < 120; c++) {
            int nin = 1 + r.nextInt(5);
            int dffs = r.nextInt(4);
            Netlist n = NetlistParser.parse(Circuits.random(r, nin, 3 + r.nextInt(25), dffs));
            int bound = Timing.criticalPath(n).delay();
            Simulator sim = new Simulator(n);
            Map<String, Boolean> in = new HashMap<>();
            Map<String, Boolean> state = new HashMap<>();
            for (int step = 0; step < 25; step++) {
                long settle;
                if (dffs > 0 && r.nextInt(3) == 0) {
                    Map<String, Boolean> all = n.evalAll(in, state);
                    Map<String, Boolean> next = new HashMap<>();
                    for (Netlist.Gate g : n.gates()) if (g.op() == Op.DFF) next.put(g.name(), all.get(g.ins().get(0)));
                    state = next;
                    settle = sim.clock();
                    clocks++;
                } else {
                    for (String name : n.inputs()) {
                        if (r.nextInt(2) == 0) {
                            boolean v = r.nextBoolean();
                            in.put(name, v);
                            sim.setInput(name, v);
                        }
                    }
                    settle = sim.run();
                    inputRuns++;
                }
                assertTrue(settle <= bound, "circuit " + c + " step " + step + " settle " + settle + " > " + bound);
                Map<String, Boolean> all = n.evalAll(in, state);
                for (String sig : all.keySet())
                    assertEquals(all.get(sig), sim.value(sig), "circuit " + c + " step " + step + " signal " + sig);
            }
            if (sim.trace().size() > 0) glitchy++;
        }
        // outcome mix: the run actually exercised clocks, input changes and activity
        assertTrue(clocks > 300, "clocks " + clocks);
        assertTrue(inputRuns > 1000, "input runs " + inputRuns);
        assertTrue(glitchy > 100, "circuits with activity " + glitchy);
    }

    @Test
    void traceTimesAreNonDecreasingAndEveryEntryIsARealChange() {
        Random r = new Random(5);
        for (int c = 0; c < 50; c++) {
            Netlist n = NetlistParser.parse(Circuits.random(r, 3, 20, 2));
            Simulator sim = new Simulator(n);
            Map<String, Boolean> last = new HashMap<>();
            for (Netlist.Gate g : n.gates()) last.put(g.name(), sim.value(g.name()));
            for (String i : n.inputs()) last.put(i, false);
            for (int step = 0; step < 20; step++) {
                if (r.nextInt(4) == 0) sim.clock();
                else { for (String i : n.inputs()) sim.setInput(i, r.nextBoolean()); sim.run(); }
            }
            List<Simulator.Change> t = new ArrayList<>(sim.trace());
            long prev = 0;
            for (Simulator.Change e : t) {
                assertTrue(e.time() >= prev);
                prev = e.time();
                assertNotEquals(last.get(e.signal()), e.value(), "no-op change recorded: " + e);
                last.put(e.signal(), e.value());
            }
            for (String s : last.keySet()) assertEquals(last.get(s), sim.value(s));
        }
    }
}
