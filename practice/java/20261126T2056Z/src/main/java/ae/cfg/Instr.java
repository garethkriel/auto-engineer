package ae.cfg;

import java.util.List;
import java.util.Optional;

/** One three-address instruction. Branches, jumps and returns are terminators. */
public sealed interface Instr {
    /** Binary operators; {@code LT} and {@code EQ} yield 1 or 0. */
    enum Op { ADD, SUB, MUL, LT, EQ }

    record Const(String dst, long value) implements Instr { }

    record Bin(String dst, Op op, String a, String b) implements Instr { }

    record Copy(String dst, String src) implements Instr { }

    /** {@code cond != 0} goes to {@code thenLabel}, otherwise to {@code elseLabel}. */
    record Br(String cond, String thenLabel, String elseLabel) implements Instr { }

    record Jmp(String target) implements Instr { }

    record Ret(String value) implements Instr { }

    /** The variable this instruction assigns, if any. */
    default Optional<String> def() {
        return switch (this) {
            case Const c -> Optional.of(c.dst());
            case Bin b -> Optional.of(b.dst());
            case Copy c -> Optional.of(c.dst());
            case Br b -> Optional.empty();
            case Jmp j -> Optional.empty();
            case Ret r -> Optional.empty();
        };
    }

    /** Operand variables, in source order. */
    default List<String> uses() {
        return switch (this) {
            case Const c -> List.of();
            case Bin b -> List.of(b.a(), b.b());
            case Copy c -> List.of(c.src());
            case Br b -> List.of(b.cond());
            case Jmp j -> List.of();
            case Ret r -> List.of(r.value());
        };
    }

    default boolean isTerminator() {
        return this instanceof Br || this instanceof Jmp || this instanceof Ret;
    }

    /** Labels this instruction may transfer control to. */
    default List<String> targets() {
        return switch (this) {
            case Br b -> List.of(b.thenLabel(), b.elseLabel());
            case Jmp j -> List.of(j.target());
            default -> List.of();
        };
    }

    /** Text form, as accepted by {@link Function#parse}. */
    default String text() {
        return switch (this) {
            case Const c -> c.dst() + " = const " + c.value();
            case Bin b -> b.dst() + " = " + b.op().name().toLowerCase(java.util.Locale.ROOT) + " " + b.a() + ", " + b.b();
            case Copy c -> c.dst() + " = copy " + c.src();
            case Br b -> "br " + b.cond() + ", " + b.thenLabel() + ", " + b.elseLabel();
            case Jmp j -> "jmp " + j.target();
            case Ret r -> "ret " + r.value();
        };
    }
}
