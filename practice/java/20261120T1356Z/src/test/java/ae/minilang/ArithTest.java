package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ae.minilang.Ast.BinOp;
import ae.minilang.LangException.Kind;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ArithTest {
    private static final long[] SAMPLES = {
        0, 1, -1, 2, -2, 3, 7, -7, 100, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
        1L << 32, -(1L << 32), 3037000500L};

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    private static boolean fits(BigInteger b) {
        return b.bitLength() <= 63;
    }

    /** Expected value per the spec, computed with BigInteger; null means "must throw OVERFLOW". */
    private static BigInteger oracle(BinOp op, long a, long b) {
        BigInteger x = big(a);
        BigInteger y = big(b);
        BigInteger r = switch (op) {
            case ADD -> x.add(y);
            case SUB -> x.subtract(y);
            case MUL -> x.multiply(y);
            case DIV -> x.divide(y);
            case MOD -> x.remainder(y);
            case LT -> big(a < b ? 1 : 0);
            case LE -> big(a <= b ? 1 : 0);
            case GT -> big(a > b ? 1 : 0);
            case GE -> big(a >= b ? 1 : 0);
            case EQ -> big(a == b ? 1 : 0);
            case NE -> big(a != b ? 1 : 0);
            case AND, OR -> throw new IllegalArgumentException("short-circuit ops are not in Arith");
        };
        return fits(r) ? r : null;
    }

    private static void check(BinOp op, long a, long b) {
        boolean divLike = op == BinOp.DIV || op == BinOp.MOD;
        if (divLike && b == 0) {
            assertEquals(Kind.DIV_ZERO,
                assertThrows(LangException.class, () -> Arith.binary(op, a, b)).kind(), op + " " + a);
            return;
        }
        BigInteger want = oracle(op, a, b);
        String ctx = a + " " + op + " " + b;
        if (want == null) {
            assertEquals(Kind.OVERFLOW,
                assertThrows(LangException.class, () -> Arith.binary(op, a, b), ctx).kind(), ctx);
        } else {
            assertEquals(want.longValueExact(), Arith.binary(op, a, b), ctx);
        }
    }

    @Test
    void edgeGridAgainstBigInteger() {
        BinOp[] ops = {BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.DIV, BinOp.MOD,
            BinOp.LT, BinOp.LE, BinOp.GT, BinOp.GE, BinOp.EQ, BinOp.NE};
        for (BinOp op : ops) {
            for (long a : SAMPLES) {
                for (long b : SAMPLES) {
                    check(op, a, b);
                }
            }
        }
    }

    @Test
    void randomAgainstBigInteger() {
        Random rnd = new Random(42);
        BinOp[] ops = {BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.DIV, BinOp.MOD};
        for (int i = 0; i < 5000; i++) {
            long a = rnd.nextBoolean() ? rnd.nextLong() : rnd.nextInt(2000) - 1000;
            long b = rnd.nextBoolean() ? rnd.nextLong() : rnd.nextInt(2000) - 1000;
            check(ops[rnd.nextInt(ops.length)], a, b);
        }
    }

    @Test
    void minDividedByMinusOneOverflowsButMinModMinusOneIsZero() {
        assertEquals(Kind.OVERFLOW, assertThrows(LangException.class,
            () -> Arith.binary(BinOp.DIV, Long.MIN_VALUE, -1)).kind());
        assertEquals(0L, Arith.binary(BinOp.MOD, Long.MIN_VALUE, -1));
    }

    @Test
    void divisionTruncatesTowardZeroAndRemainderFollowsDividend() {
        assertEquals(-3L, Arith.binary(BinOp.DIV, -7, 2));
        assertEquals(-1L, Arith.binary(BinOp.MOD, -7, 2));
        assertEquals(1L, Arith.binary(BinOp.MOD, 7, -2));
    }

    @Test
    void unary() {
        assertEquals(-5L, Arith.neg(5));
        assertEquals(Long.MAX_VALUE, Arith.neg(-Long.MAX_VALUE));
        assertEquals(Kind.OVERFLOW,
            assertThrows(LangException.class, () -> Arith.neg(Long.MIN_VALUE)).kind());
        assertEquals(1L, Arith.not(0));
        assertEquals(0L, Arith.not(-9));
    }
}
