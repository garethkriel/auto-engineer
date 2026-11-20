package ae.minilang;

import ae.minilang.Ast.BinOp;

/** Stack-machine opcodes. Immediates follow the opcode inline in the code array. */
enum Op {
    PUSH(1), POP(0), LOAD(1), STORE(1),
    ADD(0), SUB(0), MUL(0), DIV(0), MOD(0), LT(0), LE(0), GT(0), GE(0), EQ(0), NE(0),
    NEG(0), NOT(0), JMP(1), JZ(1), CALL(2), RET(0), PRINT(0);

    private static final Op[] ALL = values();

    /** Number of inline immediates. */
    final int operands;

    Op(int operands) {
        this.operands = operands;
    }

    boolean isBinary() {
        return ordinal() >= ADD.ordinal() && ordinal() <= NE.ordinal();
    }

    /** Opcode for a code word, or null when it is not an opcode. */
    static Op decode(long word) {
        return word >= 0 && word < ALL.length ? ALL[(int) word] : null;
    }

    static Op of(BinOp op) {
        return switch (op) {
            case ADD -> ADD;
            case SUB -> SUB;
            case MUL -> MUL;
            case DIV -> DIV;
            case MOD -> MOD;
            case LT -> LT;
            case LE -> LE;
            case GT -> GT;
            case GE -> GE;
            case EQ -> EQ;
            case NE -> NE;
            case AND, OR -> throw new IllegalArgumentException("short-circuit has no opcode: " + op);
        };
    }

    BinOp binOp() {
        return switch (this) {
            case ADD -> BinOp.ADD;
            case SUB -> BinOp.SUB;
            case MUL -> BinOp.MUL;
            case DIV -> BinOp.DIV;
            case MOD -> BinOp.MOD;
            case LT -> BinOp.LT;
            case LE -> BinOp.LE;
            case GT -> BinOp.GT;
            case GE -> BinOp.GE;
            case EQ -> BinOp.EQ;
            case NE -> BinOp.NE;
            default -> throw new IllegalStateException(name() + " is not binary");
        };
    }
}
