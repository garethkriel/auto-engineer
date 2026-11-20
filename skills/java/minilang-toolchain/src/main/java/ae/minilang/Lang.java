package ae.minilang;

import ae.minilang.Ast.Program;
import ae.minilang.LangException.Kind;
import java.util.ArrayList;
import java.util.List;

/** Facade: parse, check, optionally optimise, then run on the chosen engine. */
public final class Lang {
    private Lang() { }

    /** The four interchangeable execution strategies. */
    public enum Engine { INTERPRETER, INTERPRETER_OPTIMIZED, VM, VM_OPTIMIZED }

    /** Printed values and the error kind that ended the run (null when it completed). */
    public record Result(List<Long> output, Kind error) { }

    public static final long DEFAULT_STEP_LIMIT = 5_000_000;

    public static Result run(String src, Engine engine) {
        return run(src, engine, DEFAULT_STEP_LIMIT);
    }

    public static Result run(String src, Engine engine, long stepLimit) {
        List<Long> out = new ArrayList<>();
        try {
            Program p = Parser.parse(src);
            Checker.check(p);
            boolean opt = engine == Engine.INTERPRETER_OPTIMIZED || engine == Engine.VM_OPTIMIZED;
            if (opt) {
                p = Optimizer.optimize(p);
            }
            if (engine == Engine.INTERPRETER || engine == Engine.INTERPRETER_OPTIMIZED) {
                Interp.run(p, out, stepLimit);
            } else {
                Vm.run(Compiler.compile(p), out, stepLimit);
            }
            return new Result(List.copyOf(out), null);
        } catch (LangException e) {
            return new Result(List.copyOf(out), e.kind());
        }
    }

    /** Front end plus compiler; throws {@link LangException} for static errors. */
    static Bytecode compile(String src, boolean optimize) {
        Program p = Parser.parse(src);
        Checker.check(p);
        return Compiler.compile(optimize ? Optimizer.optimize(p) : p);
    }
}
