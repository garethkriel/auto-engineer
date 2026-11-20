package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.minilang.Lang.Engine;
import ae.minilang.Lang.Result;
import ae.minilang.LangException.Kind;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DifferentialTest {
    private static final int PROGRAMS = 1500;

    private static String source(long seed) {
        return Printer.source(new ProgramGen(seed).generate());
    }

    @Test
    void generatedProgramsAreValid() {
        for (long seed = 0; seed < PROGRAMS; seed++) {
            String src = source(seed);
            Ast.Program p;
            try {
                p = Parser.parse(src);
                Checker.check(p);
            } catch (LangException e) {
                throw new AssertionError("seed " + seed + " invalid: " + e.getMessage() + "\n" + src, e);
            }
        }
    }

    @Test
    void printerRoundTripIsStable() {
        for (long seed = 0; seed < PROGRAMS; seed++) {
            String src = source(seed);
            assertEquals(src, Printer.source(Parser.parse(src)), "seed " + seed);
        }
    }

    @Test
    void allFourEnginesAgree() {
        Map<Object, Integer> mix = new java.util.HashMap<>();
        for (long seed = 0; seed < PROGRAMS; seed++) {
            String src = source(seed);
            Result ref = Lang.run(src, Engine.INTERPRETER);
            for (Engine e : Engine.values()) {
                assertEquals(ref, Lang.run(src, e), "seed " + seed + " engine " + e + "\n" + src);
            }
            mix.merge(ref.error() == null ? "OK" : ref.error(), 1, Integer::sum);
        }
        System.out.println("outcome mix: " + mix);
        assertTrue(mix.getOrDefault("OK", 0) >= PROGRAMS / 5, "too few clean runs: " + mix);
        assertTrue(mix.getOrDefault(Kind.DIV_ZERO, 0) >= 20, "too few DIV_ZERO runs: " + mix);
        assertTrue(mix.getOrDefault(Kind.OVERFLOW, 0) >= 20, "too few OVERFLOW runs: " + mix);
        assertEquals(0, mix.getOrDefault(Kind.STEP_LIMIT, 0), "generated programs must terminate");
        assertEquals(0, mix.getOrDefault(Kind.STACK_OVERFLOW, 0), "generator never recurses");
    }

    @Test
    void generatedProgramsActuallyPrintAndLoop() {
        int printing = 0;
        int loops = 0;
        for (long seed = 0; seed < PROGRAMS; seed++) {
            String src = source(seed);
            if (!Lang.run(src, Engine.INTERPRETER).output().isEmpty()) {
                printing++;
            }
            if (src.contains("while")) {
                loops++;
            }
        }
        assertTrue(printing > PROGRAMS / 3, "printing programs: " + printing);
        assertTrue(loops > PROGRAMS / 5, "programs with loops: " + loops);
    }

    @Test
    void optimisedBytecodeIsNeverLongerAndAlwaysVerifies() {
        int shorter = 0;
        for (long seed = 0; seed < PROGRAMS; seed++) {
            String src = source(seed);
            Bytecode plain = Lang.compile(src, false);
            Bytecode opt = Lang.compile(src, true);
            Verifier.verify(plain);
            Verifier.verify(opt);
            assertTrue(opt.size() <= plain.size(), "seed " + seed + ": " + opt.size() + " > " + plain.size());
            if (opt.size() < plain.size()) {
                shorter++;
            }
        }
        assertTrue(shorter > PROGRAMS / 20, "optimiser rarely helps: " + shorter);
    }

    @Test
    void optimiserIsIdempotentOnGeneratedPrograms() {
        for (long seed = 0; seed < 400; seed++) {
            Ast.Program p = Optimizer.optimize(Parser.parse(source(seed)));
            assertEquals(p, Optimizer.optimize(p), "seed " + seed);
        }
    }

    @Test
    void enginesAgreeUnderTinyStepBudgetsOnlyOnTheKind() {
        // budgets are counted differently per engine, so only "ran out" vs "finished" shapes are compared
        EnumMap<Engine, Kind> kinds = new EnumMap<>(Engine.class);
        for (Engine e : Engine.values()) {
            kinds.put(e, Lang.run("let i = 0; while (i < 1000000) { i = i + 1; } print i;", e, 500).error());
        }
        for (Kind k : kinds.values()) {
            assertEquals(Kind.STEP_LIMIT, k, kinds.toString());
        }
    }
}
