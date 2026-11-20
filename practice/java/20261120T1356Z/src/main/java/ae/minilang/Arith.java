package ae.minilang;

import ae.minilang.Ast.BinOp;
import ae.minilang.LangException.Kind;

/** The one definition of arithmetic, shared by interpreter, VM and optimiser. */
final class Arith {
    private Arith() { }

    /** Strict binary operators (everything except the short-circuit AND/OR). */
    static long binary(BinOp op, long a, long b) {
        try {
            return switch (op) {
                case ADD -> Math.addExact(a, b);
                case SUB -> Math.subtractExact(a, b);
                case MUL -> Math.multiplyExact(a, b);
                case DIV -> {
                    if (b == 0) {
                        throw new LangException(Kind.DIV_ZERO, "division by zero");
                    }
                    if (a == Long.MIN_VALUE && b == -1) {
                        throw new ArithmeticException();
                    }
                    yield a / b;
                }
                case MOD -> {
                    if (b == 0) {
                        throw new LangException(Kind.DIV_ZERO, "modulo by zero");
                    }
                    yield b == -1 ? 0 : a % b;
                }
                case LT -> a < b ? 1 : 0;
                case LE -> a <= b ? 1 : 0;
                case GT -> a > b ? 1 : 0;
                case GE -> a >= b ? 1 : 0;
                case EQ -> a == b ? 1 : 0;
                case NE -> a != b ? 1 : 0;
                case AND, OR -> throw new IllegalArgumentException("short-circuit operator " + op);
            };
        } catch (ArithmeticException e) {
            throw new LangException(Kind.OVERFLOW, "integer overflow in " + a + " " + op.symbol + " " + b);
        }
    }

    static long neg(long a) {
        if (a == Long.MIN_VALUE) {
            throw new LangException(Kind.OVERFLOW, "integer overflow in -" + a);
        }
        return -a;
    }

    static long not(long a) {
        return a == 0 ? 1 : 0;
    }
}
