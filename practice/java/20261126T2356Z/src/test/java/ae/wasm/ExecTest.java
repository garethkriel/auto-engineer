package ae.wasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExecTest {
    // ------------------------------------------------------------------ helpers

    private static Instance inst(Bin.M m) {
        return Instance.instantiate(ModuleParser.parse(m.build()));
    }

    private static Bin.M single(String sig, String locals, Bin.C body) {
        Bin.M m = new Bin.M();
        int t = m.type(sig);
        int f = m.func(t, locals, body);
        m.exportFunc("f", f);
        return m;
    }

    private static long call(Bin.M m, long... args) {
        return inst(m).invoke("f", args)[0];
    }

    private static Trap.Kind trapOf(Runnable r) {
        return assertThrows(Trap.class, r::run).kind();
    }

    /** Evaluate {@code a op b}; result type {@code out}. Returns Long or Trap.Kind. */
    private static Object bin(char ty, char out, int op, long a, long b) {
        Bin.M m = single("" + ty + ty + ":" + out, "", Bin.c().get(0).get(1).b(op));
        try {
            return call(m, a, b);
        } catch (Trap t) {
            return t.kind();
        }
    }

    private static Object un(char in, char out, int op, long a) {
        Bin.M m = single("" + in + ":" + out, "", Bin.c().get(0).b(op));
        try {
            return call(m, a);
        } catch (Trap t) {
            return t.kind();
        }
    }

    private static Arguments r(String name, char ty, int op, long a, long b, Object expected) {
        return Arguments.of(name, ty, op, a, b, expected);
    }

    private static final Trap.Kind DZ = Trap.Kind.DIV_ZERO;
    private static final Trap.Kind OVF = Trap.Kind.INT_OVERFLOW;

    // ------------------------------------------------------------------ opcode tables

    static Stream<Arguments> binaryRows() {
        int imin = Integer.MIN_VALUE;
        int imax = Integer.MAX_VALUE;
        long lmin = Long.MIN_VALUE;
        long lmax = Long.MAX_VALUE;
        return Stream.of(
            // ---- i32 arithmetic (results are sign-extended ints)
            r("i32.add wrap", 'i', 0x6A, imax, 1, (long) imin),
            r("i32.add carry", 'i', 0x6A, -1, 1, 0L),
            r("i32.sub", 'i', 0x6B, 0, 1, -1L),
            r("i32.sub wrap", 'i', 0x6B, imin, 1, (long) imax),
            r("i32.mul zero", 'i', 0x6C, 65536, 65536, 0L),
            r("i32.mul low bits", 'i', 0x6C, 65537, 65537, 131073L),   // 65537^2 = 0x1_0002_0001
            r("i32.mul neg", 'i', 0x6C, -3, 7, -21L),
            r("i32.div_s trunc", 'i', 0x6D, -7, 2, -3L),
            r("i32.div_s trunc2", 'i', 0x6D, 7, -2, -3L),
            r("i32.div_s min/1", 'i', 0x6D, imin, 1, (long) imin),
            r("i32.div_s overflow", 'i', 0x6D, imin, -1, OVF),
            r("i32.div_s zero", 'i', 0x6D, 1, 0, DZ),
            r("i32.div_u big", 'i', 0x6E, -7, 2, 2147483644L),         // 0xFFFFFFF9 / 2
            r("i32.div_u same", 'i', 0x6E, -1, -1, 1L),
            r("i32.div_u zero", 'i', 0x6E, 1, 0, DZ),
            r("i32.rem_s neg", 'i', 0x6F, -7, 2, -1L),
            r("i32.rem_s neg divisor", 'i', 0x6F, 7, -2, 1L),
            r("i32.rem_s min%-1", 'i', 0x6F, imin, -1, 0L),
            r("i32.rem_s zero", 'i', 0x6F, 5, 0, DZ),
            r("i32.rem_u big", 'i', 0x70, -7, 2, 1L),
            r("i32.rem_u mod 10", 'i', 0x70, -1, 10, 5L),              // 4294967295 % 10
            r("i32.rem_u zero", 'i', 0x70, 5, 0, DZ),
            r("i32.and", 'i', 0x71, 0xF0F0, 0xFF00, 0xF000L),
            r("i32.or", 'i', 0x72, 0xF0F0, 0xFF00, 0xFFF0L),
            r("i32.xor", 'i', 0x73, 0xF0F0, 0xFF00, 0x0FF0L),
            r("i32.shl mod 32", 'i', 0x74, 1, 33, 2L),
            r("i32.shl 31", 'i', 0x74, 1, 31, (long) imin),
            r("i32.shl 32 is 0", 'i', 0x74, 1, 32, 1L),
            r("i32.shr_s", 'i', 0x75, -8, 1, -4L),
            r("i32.shr_s 31", 'i', 0x75, imin, 31, -1L),
            r("i32.shr_u", 'i', 0x76, -8, 1, 2147483644L),
            r("i32.shr_u 31", 'i', 0x76, imin, 31, 1L),
            r("i32.shr_u 36 = 4", 'i', 0x76, -1, 36, 268435455L),
            r("i32.rotl", 'i', 0x77, 0x80000001, 1, 3L),
            r("i32.rotl 32", 'i', 0x77, 0x12345678, 32, 305419896L),
            r("i32.rotl 31", 'i', 0x77, 1, 31, (long) imin),
            r("i32.rotr", 'i', 0x78, 0x80000001, 1, -1073741824L),      // 0xC0000000
            r("i32.rotr 1", 'i', 0x78, 1, 1, (long) imin),
            // ---- i32 comparisons
            r("i32.eq", 'i', 0x46, 5, 5, 1L),
            r("i32.ne", 'i', 0x47, 5, 5, 0L),
            r("i32.lt_s", 'i', 0x48, -1, 0, 1L),
            r("i32.lt_u", 'i', 0x49, -1, 0, 0L),
            r("i32.gt_s", 'i', 0x4A, -1, 0, 0L),
            r("i32.gt_u", 'i', 0x4B, -1, 0, 1L),
            r("i32.le_s eq", 'i', 0x4C, 5, 5, 1L),
            r("i32.le_u", 'i', 0x4D, 6, 5, 0L),
            r("i32.ge_s", 'i', 0x4E, -1, -1, 1L),
            r("i32.ge_u", 'i', 0x4F, 0, -1, 0L),
            r("i32.lt_s min/max", 'i', 0x48, imin, imax, 1L),
            r("i32.gt_u min/max", 'i', 0x4B, imin, imax, 1L),
            // ---- i64 arithmetic
            r("i64.add wrap", 'l', 0x7C, lmax, 1, lmin),
            r("i64.sub", 'l', 0x7D, 0, 1, -1L),
            r("i64.mul zero", 'l', 0x7E, 1L << 32, 1L << 32, 0L),
            r("i64.mul low bits", 'l', 0x7E, 0x100000001L, 0x100000001L, 8589934593L), // 2^33 + 1
            r("i64.div_s trunc", 'l', 0x7F, -7, 2, -3L),
            r("i64.div_s overflow", 'l', 0x7F, lmin, -1, OVF),
            r("i64.div_s zero", 'l', 0x7F, 1, 0, DZ),
            r("i64.div_u big", 'l', 0x80, -7, 2, 9223372036854775804L),
            r("i64.div_u zero", 'l', 0x80, 1, 0, DZ),
            r("i64.rem_s", 'l', 0x81, -7, 2, -1L),
            r("i64.rem_s min%-1", 'l', 0x81, lmin, -1, 0L),
            r("i64.rem_u", 'l', 0x82, -7, 2, 1L),
            r("i64.rem_u zero", 'l', 0x82, 1, 0, DZ),
            r("i64.and", 'l', 0x83, 0xFF00FF00FF00FF00L, 0x0FF00FF00FF00FF0L, 0x0F000F000F000F00L),
            r("i64.or", 'l', 0x84, 0xFF00FF00FF00FF00L, 0x0FF00FF00FF00FF0L, 0xFFF0FFF0FFF0FFF0L),
            r("i64.xor", 'l', 0x85, 0xFF00FF00FF00FF00L, 0x0FF00FF00FF00FF0L, 0xF0F0F0F0F0F0F0F0L),
            r("i64.shl mod 64", 'l', 0x86, 1, 65, 2L),
            r("i64.shl 63", 'l', 0x86, 1, 63, lmin),
            r("i64.shr_s", 'l', 0x87, -8, 1, -4L),
            r("i64.shr_u 63", 'l', 0x88, -1, 63, 1L),
            r("i64.shr_u", 'l', 0x88, -8, 1, 9223372036854775804L),
            r("i64.rotl", 'l', 0x89, 0x8000000000000001L, 1, 3L),
            r("i64.rotr", 'l', 0x8A, 0x8000000000000001L, 1, -4611686018427387904L), // 0xC000...
            r("i64.rotl 64", 'l', 0x89, 0x0123456789ABCDEFL, 64, 0x0123456789ABCDEFL),
            // ---- i64 comparisons (result i32; full 64 bits compared)
            r("i64.eq high bits", 'l', 0x51, 1L << 40, 1L << 40, 1L),
            r("i64.eq differ in high bits", 'l', 0x51, 1L << 40, 0, 0L),
            r("i64.ne high bits", 'l', 0x52, 1L << 32, 0, 1L),
            r("i64.lt_s", 'l', 0x53, -1, 0, 1L),
            r("i64.lt_u", 'l', 0x54, -1, 0, 0L),
            r("i64.gt_s", 'l', 0x55, -1, 0, 0L),
            r("i64.gt_u", 'l', 0x56, -1, 0, 1L),
            r("i64.le_s", 'l', 0x57, 5, 5, 1L),
            r("i64.le_u", 'l', 0x58, 6, 5, 0L),
            r("i64.ge_s", 'l', 0x59, -1, -1, 1L),
            r("i64.ge_u", 'l', 0x5A, 0, -1, 0L)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("binaryRows")
    void binaryOps(String name, char ty, int op, long a, long b, Object expected) {
        boolean comparison = (ty == 'i' && op >= 0x46 && op <= 0x4F) || (ty == 'l' && op >= 0x51 && op <= 0x5A);
        char out = comparison ? 'i' : ty;
        assertEquals(expected, bin(ty, out, op, a, b), name);
    }

    private static Arguments u(String name, char in, char out, int op, long a, long expected) {
        return Arguments.of(name, in, out, op, a, expected);
    }

    static Stream<Arguments> unaryRows() {
        return Stream.of(
            u("i32.eqz 0", 'i', 'i', 0x45, 0, 1),
            u("i32.eqz 7", 'i', 'i', 0x45, 7, 0),
            u("i32.eqz min", 'i', 'i', 0x45, Integer.MIN_VALUE, 0),
            u("i32.clz 0", 'i', 'i', 0x67, 0, 32),
            u("i32.clz 1", 'i', 'i', 0x67, 1, 31),
            u("i32.clz -1", 'i', 'i', 0x67, -1, 0),
            u("i32.clz 2^16", 'i', 'i', 0x67, 0x10000, 15),
            u("i32.ctz 0", 'i', 'i', 0x68, 0, 32),
            u("i32.ctz 8", 'i', 'i', 0x68, 8, 3),
            u("i32.ctz min", 'i', 'i', 0x68, Integer.MIN_VALUE, 31),
            u("i32.popcnt -1", 'i', 'i', 0x69, -1, 32),
            u("i32.popcnt 0x0F0F", 'i', 'i', 0x69, 0x0F0F, 8),
            u("i32.popcnt 0", 'i', 'i', 0x69, 0, 0),
            u("i32.extend8_s neg", 'i', 'i', 0xC0, 0x80, -128),
            u("i32.extend8_s pos (high bits ignored)", 'i', 'i', 0xC0, 0x17F, 127),
            u("i32.extend8_s ff", 'i', 'i', 0xC0, 0xFF, -1),
            u("i32.extend16_s neg", 'i', 'i', 0xC1, 0x8000, -32768),
            u("i32.extend16_s pos", 'i', 'i', 0xC1, 0x12345, 9029),     // 0x2345
            u("i32.extend16_s ffff", 'i', 'i', 0xC1, 0xFFFF, -1),
            u("i64.eqz 0", 'l', 'i', 0x50, 0, 1),
            u("i64.eqz 2^32", 'l', 'i', 0x50, 1L << 32, 0),
            u("i64.clz 0", 'l', 'l', 0x79, 0, 64),
            u("i64.clz 1", 'l', 'l', 0x79, 1, 63),
            u("i64.clz 2^40", 'l', 'l', 0x79, 1L << 40, 23),
            u("i64.ctz 0", 'l', 'l', 0x7A, 0, 64),
            u("i64.ctz 2^40", 'l', 'l', 0x7A, 1L << 40, 40),
            u("i64.popcnt -1", 'l', 'l', 0x7B, -1, 64),
            u("i64.popcnt bytes", 'l', 'l', 0x7B, 0x0101010101010101L, 8),
            u("i32.wrap_i64", 'l', 'i', 0xA7, 0x100000005L, 5),
            u("i32.wrap_i64 ffffffff", 'l', 'i', 0xA7, 0xFFFFFFFFL, -1),
            u("i32.wrap_i64 2^40", 'l', 'i', 0xA7, 1L << 40, 0),
            u("i64.extend_i32_s -1", 'i', 'l', 0xAC, -1, -1),
            u("i64.extend_i32_s max", 'i', 'l', 0xAC, Integer.MAX_VALUE, 2147483647L),
            u("i64.extend_i32_u -1", 'i', 'l', 0xAD, -1, 4294967295L),
            u("i64.extend_i32_u min", 'i', 'l', 0xAD, Integer.MIN_VALUE, 2147483648L),
            u("i64.extend8_s", 'l', 'l', 0xC2, 0x1FF, -1),
            u("i64.extend8_s pos", 'l', 'l', 0xC2, 0x7F, 127),
            u("i64.extend16_s pos", 'l', 'l', 0xC3, 0x7FFF, 32767),
            u("i64.extend16_s neg", 'l', 'l', 0xC3, 0x8000, -32768),
            u("i64.extend32_s neg", 'l', 'l', 0xC4, 0x80000000L, -2147483648L),
            u("i64.extend32_s pos", 'l', 'l', 0xC4, 0x7FFFFFFFL, 2147483647L),
            u("i64.extend32_s high bits ignored", 'l', 'l', 0xC4, 0x180000000L, -2147483648L)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unaryRows")
    void unaryOps(String name, char in, char out, int op, long a, long expected) {
        assertEquals((Object) expected, un(in, out, op, a), name);
    }

    @Test
    void i32ValuesAreSignExtendedOnOutputAndMaskedOnInput() {
        Bin.M m = single("i:i", "", Bin.c().get(0));
        Instance i = inst(m);
        assertEquals(-1L, i.invoke("f", 0xFFFFFFFFL)[0]);
        assertEquals(-1L, i.invoke("f", -1L)[0]);
        assertEquals(5L, i.invoke("f", 0x1_0000_0005L)[0]); // only the low 32 bits are used
        Bin.M c = single(":i", "", Bin.c().i32(-2147483648));
        assertEquals(-2147483648L, call(c));
    }

    @Test
    void constantsRoundTripExtremes() {
        assertEquals(Long.MIN_VALUE, call(single(":l", "", Bin.c().i64(Long.MIN_VALUE))));
        assertEquals(Long.MAX_VALUE, call(single(":l", "", Bin.c().i64(Long.MAX_VALUE))));
        assertEquals(Integer.MAX_VALUE, call(single(":i", "", Bin.c().i32(Integer.MAX_VALUE))));
        assertEquals(-64L, call(single(":i", "", Bin.c().i32(-64))));
        assertEquals(64L, call(single(":i", "", Bin.c().i32(64))));
    }

    // ------------------------------------------------------------------ locals, select, drop, nop

    @Test
    void localsStartAtZeroAndParamsAreCopiedPerCall() {
        // f(a) { local x:i64; local y:i32; x = x + 5; y = y + a; return y } -> locals zero-initialised each call
        Bin.M m = single("i:i", "li", Bin.c().get(1).i64(5).b(0x7C).set(1).get(2).get(0).b(0x6A).tee(2));
        Instance i = inst(m);
        assertEquals(3L, i.invoke("f", 3)[0]);
        assertEquals(4L, i.invoke("f", 4)[0]); // would be 7 if locals leaked between calls
    }

    @Test
    void selectPicksFirstOperandOnNonZeroAndSecondOnZero() {
        Bin.C sel = Bin.c().i32(10).i32(20).get(0).b(0x1B);
        assertEquals(10L, call(single("i:i", "", sel), 1));
        assertEquals(20L, call(single("i:i", "", sel), 0));
        assertEquals(10L, call(single("i:i", "", sel), -5));
        assertEquals(10L, call(single("i:i", "", sel), 0x100));
        Bin.C dropper = Bin.c().i32(1).i32(2).b(0x1A).b(0x01).b(0x01);
        assertEquals(1L, call(single(":i", "", dropper)));
        Bin.C sel64 = Bin.c().i64(1L << 40).i64(2).get(0).b(0x1B);
        assertEquals(1L << 40, call(single("i:l", "", sel64), 1));
    }

    // ------------------------------------------------------------------ control flow programs

    @Test
    void factorialLoop() {
        Bin.C c = Bin.c().i64(1).set(1)
            .b(0x02, 0x40).b(0x03, 0x40)
            .get(0).b(0x50).brIf(1)
            .get(1).get(0).b(0x7E).set(1)
            .get(0).i64(1).b(0x7D).set(0)
            .br(0)
            .end().end()
            .get(1);
        Bin.M m = single("l:l", "l", c);
        assertEquals(1L, call(m, 0));
        assertEquals(120L, call(m, 5));
        assertEquals(2432902008176640000L, call(m, 20));
        assertEquals(-4249290049419214848L, call(m, 21)); // 21! mod 2^64 (wraps)
    }

    @Test
    void recursiveFib() {
        Bin.M m = new Bin.M();
        int t = m.type("i:i");
        Bin.C c = Bin.c().get(0).i32(2).b(0x48).b(0x04, 0x7F).get(0).b(0x05)
            .get(0).i32(1).b(0x6B).call(0).get(0).i32(2).b(0x6B).call(0).b(0x6A).end();
        int f = m.func(t, "", c);
        m.exportFunc("f", f);
        Instance i = inst(m);
        long[] expected = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55};
        for (int n = 0; n < expected.length; n++) {
            assertEquals(expected[n], i.invoke("f", n)[0], "fib " + n);
        }
        assertEquals(6765L, i.invoke("f", 20)[0]);
    }

    @Test
    void gcdLoop() {
        Bin.C c = Bin.c().b(0x02, 0x40).b(0x03, 0x40)
            .get(1).b(0x45).brIf(1)
            .get(0).get(1).b(0x70).get(1).set(0).set(1)
            .br(0).end().end().get(0);
        Bin.M m = single("ii:i", "", c);
        assertEquals(6L, call(m, 48, 18));
        assertEquals(17L, call(m, 17, 0));
        assertEquals(5L, call(m, 0, 5));
        assertEquals(1L, call(m, 1_000_000_007, 998_244_353)); // two primes
        assertEquals(1000L, call(m, 3_000_000, 7_000));
    }

    @Test
    void collatzSteps() {
        Bin.C c = Bin.c().b(0x02, 0x40).b(0x03, 0x40)
            .get(0).i64(1).b(0x51).brIf(1)
            .get(0).i64(1).b(0x83).b(0x50)
            .b(0x04, 0x40).get(0).i64(1).b(0x88).set(0)
            .b(0x05).get(0).i64(3).b(0x7E).i64(1).b(0x7C).set(0).end()
            .get(1).i32(1).b(0x6A).set(1)
            .br(0).end().end().get(1);
        Bin.M m = single("l:i", "i", c);
        assertEquals(0L, call(m, 1));
        assertEquals(8L, call(m, 6));
        assertEquals(111L, call(m, 27));
        assertEquals(524L, call(m, 837799)); // Project Euler 14: the longest chain below one million
    }

    @Test
    void sieveInLinearMemory() {
        Bin.C c = Bin.c().i32(2).set(1)
            .b(0x02, 0x40).b(0x03, 0x40)
            .get(1).get(0).b(0x4F).brIf(1)
            .get(1).mem(0x2D, 0, 0).b(0x45)
            .b(0x04, 0x40)
            .get(3).i32(1).b(0x6A).set(3)
            .get(1).get(1).b(0x6C).set(2)
            .b(0x02, 0x40).b(0x03, 0x40)
            .get(2).get(0).b(0x4F).brIf(1)
            .get(2).i32(1).mem(0x3A, 0, 0)
            .get(2).get(1).b(0x6A).set(2)
            .br(0).end().end()
            .end()
            .get(1).i32(1).b(0x6A).set(1)
            .br(0).end().end()
            .get(3);
        Bin.M m = single("i:i", "iii", c).mem(1, -1);
        assertEquals(0L, call(m, 2));
        assertEquals(1L, call(m, 3));
        assertEquals(25L, call(m, 100));
        assertEquals(168L, call(m, 1000));
        assertEquals(1229L, call(m, 10000));
        assertEquals(6542L, call(m, 65536));
    }

    @Test
    void brTableDispatch() {
        Bin.C c = Bin.c()
            .b(0x02, 0x40).b(0x02, 0x40).b(0x02, 0x40).b(0x02, 0x40)
            .get(0).brTable(3, 0, 1, 2)
            .end().i32(10).ret()
            .end().i32(20).ret()
            .end().i32(30).ret()
            .end().i32(99);
        Bin.M m = single("i:i", "", c);
        Instance i = inst(m);
        assertEquals(10L, i.invoke("f", 0)[0]);
        assertEquals(20L, i.invoke("f", 1)[0]);
        assertEquals(30L, i.invoke("f", 2)[0]);
        assertEquals(99L, i.invoke("f", 3)[0]);
        assertEquals(99L, i.invoke("f", -1)[0]);          // 0xFFFFFFFF is unsigned, out of range -> default
        assertEquals(99L, i.invoke("f", 1_000_000)[0]);
    }

    @Test
    void brWithValueDiscardsExtraOperandsAndKeepsOuterStack() {
        // 100 + block(result i32) { 7 8 9 br 0 } = 109
        Bin.C c = Bin.c().i32(100).b(0x02, 0x7F).i32(7).i32(8).i32(9).br(0).end().b(0x6A);
        assertEquals(109L, call(single(":i", "", c)));
        // br_if taken keeps the value, not taken falls through with the value as well
        Bin.C taken = Bin.c().b(0x02, 0x7F).i32(5).get(0).brIf(0).b(0x1A).i32(6).end();
        Bin.M m = single("i:i", "", taken);
        assertEquals(5L, call(m, 1));
        assertEquals(6L, call(m, 0));
    }

    @Test
    void loopBranchesBackAndBlockBranchesForward() {
        // sum 1..n with a loop whose label carries no value; nested block exit via br_if 1
        Bin.C c = Bin.c().b(0x02, 0x40).b(0x03, 0x40)
            .get(0).b(0x45).brIf(1)
            .get(1).get(0).b(0x6A).set(1)
            .get(0).i32(1).b(0x6B).set(0)
            .br(0).end().end().get(1);
        Bin.M m = single("i:i", "i", c);
        assertEquals(0L, call(m, 0));
        assertEquals(5050L, call(m, 100));
        assertEquals(1784293664L, call(m, 1_000_000)); // 500000500000 mod 2^32 (fits a positive i32)
    }

    @Test
    void ifElseBothArmsAndNested() {
        // sign(x): if x<0 -1 else if x>0 1 else 0
        Bin.C c = Bin.c().get(0).i32(0).b(0x48).b(0x04, 0x7F).i32(-1)
            .b(0x05).get(0).i32(0).b(0x4A).b(0x04, 0x7F).i32(1).b(0x05).i32(0).end().end();
        Bin.M m = single("i:i", "", c);
        assertEquals(-1L, call(m, -9));
        assertEquals(1L, call(m, 9));
        assertEquals(0L, call(m, 0));
        // if without else, empty block type, with a side effect in a local
        Bin.C c2 = Bin.c().get(0).b(0x04, 0x40).i32(7).set(1).end().get(1);
        Bin.M m2 = single("i:i", "i", c2);
        assertEquals(7L, call(m2, 1));
        assertEquals(0L, call(m2, 0));
    }

    @Test
    void returnFromNestedBlocksInsideCalleeLeavesCallerStackIntact() {
        Bin.M m = new Bin.M();
        int t = m.type("i:i");
        int callee = m.func(t, "", Bin.c().b(0x02, 0x40).b(0x02, 0x40).i32(1).i32(2).i32(3).get(0).b(0x04, 0x40)
            .i32(42).ret().end().b(0x1A, 0x1A, 0x1A).end().end().i32(0));
        int t2 = m.type(":i");
        int caller = m.func(t2, "", Bin.c().i32(1000).i32(500).i32(1).call(callee).b(0x6A).b(0x6A));
        m.exportFunc("g", caller);
        assertEquals(1542L, inst(m).invoke("g")[0]);
    }

    @Test
    void callsPassArgumentsInOrder() {
        Bin.M m = new Bin.M();
        int sub = m.func(m.type("ii:i"), "", Bin.c().get(0).get(1).b(0x6B));
        int main = m.func(m.type(":i"), "", Bin.c().i32(10).i32(3).call(sub));
        m.exportFunc("f", main);
        assertEquals(7L, inst(m).invoke("f")[0]);
    }

    @Test
    void globalsAreSharedAcrossCalls() {
        Bin.M m = new Bin.M();
        int g = m.global('i', true, 40);
        int gl = m.global('l', false, -5);
        int f = m.func(m.type(":i"), "", Bin.c().gget(g).i32(1).b(0x6A).gset(g).gget(g));
        m.exportFunc("inc", f);
        m.export("g", 3, g);
        m.export("gl", 3, gl);
        Instance i = inst(m);
        assertEquals(41L, i.invoke("inc")[0]);
        assertEquals(42L, i.invoke("inc")[0]);
        assertEquals(42L, i.global("g"));
        assertEquals(-5L, i.global("gl"));
    }

    @Test
    void startFunctionRunsOnceAtInstantiation() {
        Bin.M m = new Bin.M();
        int g = m.global('i', true, 0);
        int s = m.func(m.type(":"), "", Bin.c().gget(g).i32(1).b(0x6A).gset(g));
        m.start(s);
        m.export("g", 3, g);
        Instance i = inst(m);
        assertEquals(1L, i.global("g"));
    }

    @Test
    void startFunctionTrapPropagatesFromInstantiate() {
        Bin.M m = new Bin.M();
        int s = m.func(m.type(":"), "", Bin.c().b(0x00));
        m.start(s);
        Module parsed = ModuleParser.parse(m.build());
        assertEquals(Trap.Kind.UNREACHABLE, trapOf(() -> Instance.instantiate(parsed)));
    }

    // ------------------------------------------------------------------ memory

    private static Bin.M memModule(String sig, Bin.C body) {
        return single(sig, "", body).mem(1, -1);
    }

    @Test
    void littleEndianLayoutAndSubWordLoads() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        // setup: i64.store 0x0102030405060708 at 0; then a family of loads selected by param
        int setup = m.func(m.type(":"), "", Bin.c().i32(0).i64(0x0102030405060708L).mem(0x37, 3, 0));
        m.export("setup", 0, setup);
        m.export("l32_0", 0, m.func(m.type(":i"), "", Bin.c().i32(0).mem(0x28, 2, 0)));
        m.export("l32_4", 0, m.func(m.type(":i"), "", Bin.c().i32(4).mem(0x28, 2, 0)));
        m.export("l32_off", 0, m.func(m.type(":i"), "", Bin.c().i32(1).mem(0x28, 0, 3))); // address 1 + offset 3 = 4
        m.export("l16u_1", 0, m.func(m.type(":i"), "", Bin.c().i32(1).mem(0x2F, 0, 0)));
        m.export("l16s_6", 0, m.func(m.type(":i"), "", Bin.c().i32(6).mem(0x2E, 1, 0)));
        m.export("l8u_7", 0, m.func(m.type(":i"), "", Bin.c().i32(7).mem(0x2D, 0, 0)));
        m.export("l64", 0, m.func(m.type(":l"), "", Bin.c().i32(0).mem(0x29, 3, 0)));
        Instance i = inst(m);
        i.invoke("setup");
        assertEquals(0x05060708L, i.invoke("l32_0")[0]);
        assertEquals(0x01020304L, i.invoke("l32_4")[0]);
        assertEquals(0x01020304L, i.invoke("l32_off")[0]);
        assertEquals(0x0607L, i.invoke("l16u_1")[0]);     // bytes 07 06 at addresses 1,2
        assertEquals(0x0102L, i.invoke("l16s_6")[0]);     // bytes 02 01 at addresses 6,7
        assertEquals(1L, i.invoke("l8u_7")[0]);
        assertEquals(0x0102030405060708L, i.invoke("l64")[0]);
        assertArrayEquals(new byte[] {8, 7, 6, 5, 4, 3, 2, 1}, i.readMemory(0, 8));
    }

    @Test
    void signAndZeroExtensionOfSubWordLoads() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.export("s8", 0, m.func(m.type(":"), "", Bin.c().i32(0).i32(0xFF).mem(0x3A, 0, 0)));
        m.export("s16", 0, m.func(m.type(":"), "", Bin.c().i32(4).i32(0x8000).mem(0x3B, 1, 0)));
        m.export("l8s", 0, m.func(m.type(":i"), "", Bin.c().i32(0).mem(0x2C, 0, 0)));
        m.export("l8u", 0, m.func(m.type(":i"), "", Bin.c().i32(0).mem(0x2D, 0, 0)));
        m.export("l16s", 0, m.func(m.type(":i"), "", Bin.c().i32(4).mem(0x2E, 1, 0)));
        m.export("l16u", 0, m.func(m.type(":i"), "", Bin.c().i32(4).mem(0x2F, 1, 0)));
        Instance i = inst(m);
        i.invoke("s8");
        i.invoke("s16");
        assertEquals(-1L, i.invoke("l8s")[0]);
        assertEquals(255L, i.invoke("l8u")[0]);
        assertEquals(-32768L, i.invoke("l16s")[0]);
        assertEquals(32768L, i.invoke("l16u")[0]);
        assertArrayEquals(new byte[] {0, (byte) 0x80}, i.readMemory(4, 2));
    }

    @Test
    void boundsAreExactAndEffectiveAddressDoesNotWrap() {
        Bin.M m = memModule("i:i", Bin.c().get(0).mem(0x28, 2, 0));
        Instance i = inst(m);
        assertEquals(0L, i.invoke("f", 65532)[0]);                   // last in-bounds 4-byte slot
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("f", 65533)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("f", 65536)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("f", -1)));
        Bin.M l64 = memModule("i:l", Bin.c().get(0).mem(0x29, 3, 0));
        Instance j = inst(l64);
        assertEquals(0L, j.invoke("f", 65528)[0]);
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> j.invoke("f", 65529)));
        Bin.M l8 = memModule("i:i", Bin.c().get(0).mem(0x2D, 0, 0));
        Instance k = inst(l8);
        assertEquals(0L, k.invoke("f", 65535)[0]);
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> k.invoke("f", 65536)));
        // base 0xFFFFFFFF + offset 1 = 2^32: must trap, not wrap to address 0
        Bin.M wrap = memModule("i:i", Bin.c().get(0).mem(0x28, 0, 1));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> inst(wrap).invoke("f", -1)));
        // huge offset with base 0
        Bin.M huge = memModule("i:i", Bin.c().get(0).mem(0x28, 0, 0xFFFFFFFFL));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> inst(huge).invoke("f", 0)));
        // offset pushes an otherwise fine base across the end
        Bin.M off = memModule("i:i", Bin.c().get(0).mem(0x28, 2, 10));
        Instance o = inst(off);
        assertEquals(0L, o.invoke("f", 65522)[0]);                   // 65522 + 10 + 4 = 65536
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> o.invoke("f", 65523)));
    }

    @Test
    void trappingStoreWritesNothing() {
        // i64.store at 65530 needs 8 bytes but only 6 remain: no partial write
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.export("st", 0, m.func(m.type(":"), "", Bin.c().i32(65530).i64(-1).mem(0x37, 3, 0)));
        Instance i = inst(m);
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("st")));
        assertArrayEquals(new byte[6], i.readMemory(65530, 6));
    }

    @Test
    void memoryAndGlobalsKeepStateAfterATrap() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        int g = m.global('i', true, 0);
        m.export("go", 0, m.func(m.type(":"), "", Bin.c().i32(0).i32(7).mem(0x3A, 0, 0).i32(9).gset(g).b(0x00)));
        m.export("g", 3, g);
        m.export("ok", 0, m.func(m.type(":i"), "", Bin.c().i32(5)));
        Instance i = inst(m);
        assertEquals(Trap.Kind.UNREACHABLE, trapOf(() -> i.invoke("go")));
        assertEquals(7, i.readMemory(0, 1)[0]);
        assertEquals(9L, i.global("g"));
        assertEquals(5L, i.invoke("ok")[0]);                         // instance still usable
    }

    @Test
    void memorySizeAndGrow() {
        Bin.M m = new Bin.M();
        m.mem(1, 3);
        m.export("size", 0, m.func(m.type(":i"), "", Bin.c().b(0x3F, 0)));
        m.export("grow", 0, m.func(m.type("i:i"), "", Bin.c().get(0).b(0x40, 0)));
        m.export("store", 0, m.func(m.type("ii:"), "", Bin.c().get(0).get(1).mem(0x36, 2, 0)));
        m.export("load", 0, m.func(m.type("i:i"), "", Bin.c().get(0).mem(0x28, 2, 0)));
        Instance i = inst(m);
        i.invoke("store", 100, 0x11223344);
        assertEquals(1L, i.invoke("size")[0]);
        assertEquals(1L, i.invoke("grow", 1)[0]);                    // returns the old size
        assertEquals(2L, i.invoke("size")[0]);
        assertEquals(0x11223344L, i.invoke("load", 100)[0]);          // preserved
        assertEquals(0L, i.invoke("load", 65536 + 100)[0]);           // new pages are zero
        assertEquals(-1L, i.invoke("grow", 5)[0]);                   // beyond the declared max
        assertEquals(2L, i.invoke("size")[0]);                       // failed grow changes nothing
        assertEquals(2L, i.invoke("grow", 0)[0]);                    // grow(0) reports the size
        assertEquals(2L, i.invoke("grow", 1)[0]);
        assertEquals(-1L, i.invoke("grow", 1)[0]);                   // already at max 3
        assertEquals(3, i.memoryPages());
        assertEquals(-1L, i.invoke("grow", -1)[0]);                  // 0xFFFFFFFF pages is unsigned and huge
        i.invoke("store", 3 * 65536 - 4, 77);
        assertEquals(77L, i.invoke("load", 3 * 65536 - 4)[0]);
    }

    @Test
    void growIsCappedAtTheImplementationLimitWithoutDeclaredMax() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.export("grow", 0, m.func(m.type("i:i"), "", Bin.c().get(0).b(0x40, 0)));
        Instance i = inst(m);
        assertEquals(1L, i.invoke("grow", 255)[0]);                   // 1 + 255 = 256 pages is allowed
        assertEquals(256, i.memoryPages());
        assertEquals(-1L, i.invoke("grow", 1)[0]);
    }

    @Test
    void minimumAboveImplementationLimitFailsToInstantiate() {
        Bin.M m = new Bin.M();
        m.mem(257, -1);
        Module parsed = ModuleParser.parse(m.build());
        ModuleException e = assertThrows(ModuleException.class, () -> Instance.instantiate(parsed));
        assertEquals(ModuleException.Kind.LINK, e.kind());
        assertTrue(e.getMessage().contains("memory too large"), e.getMessage());
    }

    @Test
    void dataSegmentsInitialiseMemory() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.data(10, new byte[] {1, 2, 3});
        m.data(11, new byte[] {9}); // later segment overwrites
        Instance i = inst(m);
        assertArrayEquals(new byte[] {0, 1, 9, 3, 0}, i.readMemory(9, 5));
    }

    // ------------------------------------------------------------------ traps, fuel, API errors

    @Test
    void unreachableTraps() {
        assertEquals(Trap.Kind.UNREACHABLE, trapOf(() -> call(single(":", "", Bin.c().b(0x00)))));
    }

    @Test
    void infiniteRecursionExhaustsTheCallStack() {
        Bin.M m = new Bin.M();
        int f = m.func(m.type(":"), "", Bin.c().call(0));
        m.exportFunc("f", f);
        Instance i = inst(m);
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> i.invoke("f")));
        // and the instance is usable afterwards (depth counter reset)
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> i.invoke("f")));
    }

    @Test
    void deepButBoundedRecursionSucceeds() {
        // count(n) = n == 0 ? 0 : 1 + count(n-1), depth 250 is below the 300 limit, depth 400 is above
        Bin.M m = new Bin.M();
        Bin.C c = Bin.c().get(0).b(0x45).b(0x04, 0x7F).i32(0).b(0x05)
            .i32(1).get(0).i32(1).b(0x6B).call(0).b(0x6A).end();
        int f = m.func(m.type("i:i"), "", c);
        m.exportFunc("f", f);
        Instance i = inst(m);
        assertEquals(250L, i.invoke("f", 250)[0]);
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> i.invoke("f", 400)));
    }

    @Test
    void operandStackOverflowIsATrapNotACrash() {
        // each frame reserves many operand slots: a function pushing 5000 constants, recursing 299 deep
        Bin.C c = Bin.c();
        for (int k = 0; k < 5000; k++) {
            c.i32(k);
        }
        for (int k = 0; k < 5000; k++) {
            c.b(0x1A);
        }
        c.call(0);
        Bin.M m = new Bin.M();
        int f = m.func(m.type(":"), "", c);
        m.exportFunc("f", f);
        Instance i = inst(m);
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> i.invoke("f")));
    }

    @Test
    void fuelBoundsInfiniteLoopsAndCountsBranchesAndCalls() {
        Bin.M m = new Bin.M();
        int spin = m.func(m.type(":"), "", Bin.c().b(0x03, 0x40).br(0).end());
        m.exportFunc("spin", spin);
        Module parsed = ModuleParser.parse(m.build());
        Instance i = Instance.instantiate(parsed, 1000);
        assertEquals(Trap.Kind.OUT_OF_FUEL, trapOf(() -> i.invoke("spin")));

        // exact accounting: a loop doing n taken br_ifs then falling out costs n + 0 for the exit branch
        Bin.M c = new Bin.M();
        // f(n): block loop n==0 -> br_if 1 (taken once at the end); n-=1; br 0 end end
        Bin.C body = Bin.c().b(0x02, 0x40).b(0x03, 0x40).get(0).b(0x45).brIf(1)
            .get(0).i32(1).b(0x6B).set(0).br(0).end().end();
        int f = c.func(c.type("i:"), "", body);
        c.exportFunc("f", f);
        Module cm = ModuleParser.parse(c.build());
        // n iterations: n unconditional `br 0` + 1 taken `br_if 1` = n + 1 fuel
        Instance exact = Instance.instantiate(cm, 11);
        exact.invoke("f", 10);                                       // costs 11: succeeds with fuel to spare 0
        Instance tight = Instance.instantiate(cm, 10);
        assertEquals(Trap.Kind.OUT_OF_FUEL, trapOf(() -> tight.invoke("f", 10)));

        // calls cost one each
        Bin.M callM = new Bin.M();
        int leaf = callM.func(callM.type(":"), "", Bin.c());
        int two = callM.func(callM.type(":"), "", Bin.c().call(leaf).call(leaf));
        callM.exportFunc("two", two);
        Module cmod = ModuleParser.parse(callM.build());
        Instance.instantiate(cmod, 2).invoke("two");
        Instance low = Instance.instantiate(cmod, 1);
        assertEquals(Trap.Kind.OUT_OF_FUEL, trapOf(() -> low.invoke("two")));
    }

    @Test
    void untakenBrIfDoesNotConsumeFuel() {
        Bin.M m = single("i:", "", Bin.c().b(0x02, 0x40).get(0).brIf(0).get(0).brIf(0).get(0).brIf(0).end());
        Module parsed = ModuleParser.parse(m.build());
        Instance.instantiate(parsed, 0).invoke("f", 0); // three untaken branches: no fuel needed
        Instance one = Instance.instantiate(parsed, 0);
        assertEquals(Trap.Kind.OUT_OF_FUEL, trapOf(() -> one.invoke("f", 1)));
    }

    @Test
    void invokeArgumentErrors() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        int f = m.func(m.type("ii:i"), "", Bin.c().get(0));
        m.exportFunc("f", f);
        m.export("mem", 2, 0);
        Instance i = inst(m);
        assertThrows(IllegalArgumentException.class, () -> i.invoke("f", 1));
        assertThrows(IllegalArgumentException.class, () -> i.invoke("f", 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> i.invoke("nope"));
        assertThrows(IllegalArgumentException.class, () -> i.invoke("mem"));
        assertThrows(IllegalArgumentException.class, () -> i.global("f"));
        assertEquals(1L, i.invoke("f", 1, 2)[0]);
    }

    @Test
    void voidFunctionsReturnEmptyArray() {
        Bin.M m = single(":", "", Bin.c());
        assertEquals(0, inst(m).invoke("f").length);
    }

    // ------------------------------------------------------------------ boundary tests added for mutation survivors

    @Test
    void callDepthLimitIsExactlyThreeHundredFrames() {
        Bin.M m = new Bin.M();
        Bin.C c = Bin.c().get(0).b(0x45).b(0x04, 0x7F).i32(0).b(0x05)
            .i32(1).get(0).i32(1).b(0x6B).call(0).b(0x6A).end();
        m.exportFunc("f", m.func(m.type("i:i"), "", c));
        Instance i = inst(m);
        assertEquals(299L, i.invoke("f", 299)[0]);                       // entry frame + 299 nested = 300 frames
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> i.invoke("f", 300))); // 301st frame
    }

    private static Bin.M pushDrop(int count) {
        Bin.C c = Bin.c();
        for (int k = 0; k < count; k++) {
            c.i32(k);
        }
        for (int k = 0; k < count; k++) {
            c.b(0x1A);
        }
        Bin.M m = new Bin.M();
        m.exportFunc("f", m.func(m.type(":"), "", c));
        return m;
    }

    @Test
    void operandStackCapacityBoundaryIsExact() {
        inst(pushDrop(65536)).invoke("f");                               // exactly fills the 65536 slots
        Instance over = inst(pushDrop(65537));
        assertEquals(Trap.Kind.STACK_EXHAUSTED, trapOf(() -> over.invoke("f")));
    }

    @Test
    void i32LoadKeepsTheSignAndStore8TouchesOneByte() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.export("neg", 0, m.func(m.type(":i"), "", Bin.c().i32(0).i32(-2).mem(0x36, 2, 0).i32(0).mem(0x28, 2, 0)));
        m.export("s8", 0, m.func(m.type(":i"), "",
            Bin.c().i32(0).i32(0x11223344).mem(0x36, 2, 0).i32(0).i32(0xAA).mem(0x3A, 0, 0).i32(0).mem(0x28, 2, 0)));
        m.export("s16", 0, m.func(m.type(":i"), "",
            Bin.c().i32(0).i32(0x11223344).mem(0x36, 2, 0).i32(0).i32(0xBBCC).mem(0x3B, 1, 0).i32(0).mem(0x28, 2, 0)));
        m.export("s64", 0, m.func(m.type(":i"), "",
            Bin.c().i32(8).i64(-1L).mem(0x37, 3, 0).i32(8).mem(0x2D, 0, 7)));
        Instance i = inst(m);
        assertEquals(-2L, i.invoke("neg")[0]);
        assertEquals(0x112233AAL, i.invoke("s8")[0]);                    // bytes 1..3 untouched
        assertEquals(0x1122BBCCL & 0xFFFFFFFFL, i.invoke("s16")[0]);     // bytes 2..3 untouched
        assertEquals(255L, i.invoke("s64")[0]);                          // last byte of the 8 stored
    }

    @Test
    void anyNonZeroI32IsTrueForIfBrIfAndSelect() {
        Bin.M ifm = single("i:i", "", Bin.c().get(0).b(0x04, 0x7F).i32(1).b(0x05).i32(2).end());
        Bin.M brif = single("i:i", "", Bin.c().b(0x02, 0x7F).i32(1).get(0).brIf(0).b(0x1A).i32(2).end());
        Bin.M sel = single("i:i", "", Bin.c().i32(1).i32(2).get(0).b(0x1B));
        for (long cond : new long[] {-1, Integer.MIN_VALUE, 0x80000000L, 5, 0x100}) {
            assertEquals(1L, call(ifm, cond), "if " + cond);
            assertEquals(1L, call(brif, cond), "br_if " + cond);
            assertEquals(1L, call(sel, cond), "select " + cond);
        }
        assertEquals(2L, call(ifm, 0));
        assertEquals(2L, call(brif, 0));
        assertEquals(2L, call(sel, 0));
        assertEquals(2L, call(ifm, 0x1_0000_0000L)); // only the low 32 bits count
    }

    @Test
    void brTableCostsOneFuelPerExecution() {
        // f(n): block loop { if n==0 br_if 1; n -= 1; 0; br_table [0] default 0 (loop) }  -> n br_tables + 1 br_if
        Bin.C body = Bin.c().b(0x02, 0x40).b(0x03, 0x40).get(0).b(0x45).brIf(1)
            .get(0).i32(1).b(0x6B).set(0).i32(0).brTable(0, 0).end().end();
        Bin.M m = new Bin.M();
        m.exportFunc("f", m.func(m.type("i:"), "", body));
        Module parsed = ModuleParser.parse(m.build());
        Instance.instantiate(parsed, 6).invoke("f", 5);                  // 5 + 1 = 6 units
        Instance tight = Instance.instantiate(parsed, 5);
        assertEquals(Trap.Kind.OUT_OF_FUEL, trapOf(() -> tight.invoke("f", 5)));
    }

    @Test
    void memoryOfExactlyTheImplementationLimitInstantiates() {
        Bin.M m = new Bin.M();
        m.mem(256, -1);
        assertEquals(256, inst(m).memoryPages());
    }

    @Test
    void brIfAndBrPreserveOuterOperandsAtTheirHeights() {
        // (10 + block{ 20 + block(result i32){ 5 br_if... } }): nested blocks, values below must survive
        Bin.C c = Bin.c().i32(10).b(0x02, 0x7F).i32(20).b(0x02, 0x7F).i32(5).get(0).brIf(1).b(0x1A).i32(7).end()
            .b(0x6A).end().b(0x6A);
        Bin.M m = single("i:i", "", c);
        assertEquals(15L, call(m, 1));   // taken: leaves the outer block with 5 (the 20 is discarded): 10 + 5
        assertEquals(37L, call(m, 0));   // not taken: inner yields 7; 20 + 7 = 27; 10 + 27 = 37
    }

    @Test
    void storeBoundsAreExactForEveryWidth() {
        Bin.M m = new Bin.M();
        m.mem(1, -1);
        m.export("s32", 0, m.func(m.type("i:"), "", Bin.c().get(0).i32(-1).mem(0x36, 2, 0)));
        m.export("s64", 0, m.func(m.type("i:"), "", Bin.c().get(0).i64(-1).mem(0x37, 3, 0)));
        m.export("s8", 0, m.func(m.type("i:"), "", Bin.c().get(0).i32(-1).mem(0x3A, 0, 0)));
        m.export("s16", 0, m.func(m.type("i:"), "", Bin.c().get(0).i32(-1).mem(0x3B, 1, 0)));
        Instance i = inst(m);
        i.invoke("s32", 65532);
        i.invoke("s64", 65528);
        i.invoke("s8", 65535);
        i.invoke("s16", 65534);
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("s32", 65533)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("s64", 65529)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("s8", 65536)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("s16", 65535)));
        assertEquals(Trap.Kind.OUT_OF_BOUNDS, trapOf(() -> i.invoke("s32", -1)));
    }
}
