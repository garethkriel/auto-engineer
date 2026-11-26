package ae.cfg;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reference interpreter: wrapping {@code long} arithmetic and a step budget. */
public final class Interp {
    private Interp() { }

    /**
     * Runs {@code f}; empty if more than {@code maxSteps} instructions would be executed.
     *
     * @throws IllegalStateException if an unassigned variable is read
     * @throws IllegalArgumentException if {@code args} does not match the parameter list
     */
    public static Optional<Long> run(Function f, long[] args, int maxSteps) {
        if (args.length != f.params().size()) {
            throw new IllegalArgumentException("expected " + f.params().size() + " arguments, got " + args.length);
        }
        Map<String, Long> env = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            env.put(f.params().get(i), args[i]);
        }
        Map<String, Integer> labels = f.labelIndex();
        int block = 0;
        int steps = 0;
        while (true) {
            for (Instr i : f.blocks().get(block).instrs()) {
                if (++steps > maxSteps) {
                    return Optional.empty();
                }
                switch (i) {
                    case Instr.Const c -> env.put(c.dst(), c.value());
                    case Instr.Copy c -> env.put(c.dst(), read(env, c.src()));
                    case Instr.Bin b -> env.put(b.dst(), apply(b.op(), read(env, b.a()), read(env, b.b())));
                    case Instr.Br b -> block = labels.get(read(env, b.cond()) != 0 ? b.thenLabel() : b.elseLabel());
                    case Instr.Jmp j -> block = labels.get(j.target());
                    case Instr.Ret r -> {
                        return Optional.of(read(env, r.value()));
                    }
                }
            }
        }
    }

    private static long read(Map<String, Long> env, String v) {
        Long x = env.get(v);
        if (x == null) {
            throw new IllegalStateException("read of unassigned variable " + v);
        }
        return x;
    }

    private static long apply(Instr.Op op, long a, long b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case LT -> a < b ? 1 : 0;
            case EQ -> a == b ? 1 : 0;
        };
    }
}
