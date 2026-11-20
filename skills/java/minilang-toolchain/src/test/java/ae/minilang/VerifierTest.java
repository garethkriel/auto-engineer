package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.minilang.Bytecode.Function;
import ae.minilang.LangException.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifierTest {
    private static long[] code(Object... items) {
        long[] out = new long[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] instanceof Op op ? op.ordinal() : ((Number) items[i]).longValue();
        }
        return out;
    }

    private static Bytecode prog(long[] mainCode, int mainLocals, Function... others) {
        List<Function> fs = new ArrayList<>();
        fs.add(new Function("main", 0, mainLocals, mainCode));
        fs.addAll(List.of(others));
        return new Bytecode(fs);
    }

    private static void rejected(String why, Bytecode b) {
        LangException e = assertThrows(LangException.class, () -> Verifier.verify(b), why);
        assertEquals(Kind.VERIFY, e.kind(), why);
        assertTrue(e.getMessage().contains(why), e.getMessage());
    }

    private static final long[] GOOD = code(Op.PUSH, 1, Op.PRINT, Op.PUSH, 0, Op.RET);

    @Test
    void controlLoadsAndReportsMaxStack() {
        assertEquals(1, Verifier.verify(prog(GOOD, 0))[0]);
        long[] twoDeep = code(Op.PUSH, 1, Op.PUSH, 2, Op.ADD, Op.PRINT, Op.PUSH, 0, Op.RET);
        assertEquals(2, Verifier.verify(prog(twoDeep, 0))[0]);
    }

    @Test
    void loopWithConsistentDepthIsAccepted() {
        // 0: PUSH 0; 2: STORE 0; 4: LOAD 0 ; 6: JZ 12 ; 8: JMP 4 ... classic loop shape
        long[] loop = code(Op.PUSH, 1, Op.STORE, 0, Op.LOAD, 0, Op.JZ, 12, Op.JMP, 4, Op.PUSH, 9, Op.PUSH, 0, Op.RET);
        // JZ target 12 is PUSH 0 (index: PUSH1=0..1, STORE=2..3, LOAD=4..5, JZ=6..7, JMP=8..9, PUSH 9=10..11, PUSH 0=12..13)
        assertEquals(1, Verifier.verify(prog(loop, 1))[0]);
    }

    @Test
    void stackUnderflow() {
        rejected("underflow", prog(code(Op.POP, Op.PUSH, 0, Op.RET), 0));
        rejected("underflow", prog(code(Op.PUSH, 1, Op.ADD, Op.PUSH, 0, Op.RET), 0));
    }

    @Test
    void jumpTargets() {
        rejected("jump target", prog(code(Op.JMP, 99, Op.PUSH, 0, Op.RET), 0));
        rejected("jump target", prog(code(Op.JMP, -2, Op.PUSH, 0, Op.RET), 0));
        // target 3 is the operand of the PUSH at 2
        rejected("jump target", prog(code(Op.JMP, 3, Op.PUSH, 0, Op.RET), 0));
    }

    @Test
    void inconsistentDepthAtMerge() {
        // PUSH 1; JZ L; PUSH 5; L: PUSH 0; RET  -> depth 0 via jump, 1 via fall-through
        rejected("inconsistent", prog(code(Op.PUSH, 1, Op.JZ, 6, Op.PUSH, 5, Op.PUSH, 0, Op.RET), 0));
    }

    @Test
    void slotBounds() {
        rejected("slot", prog(code(Op.LOAD, 1, Op.RET), 1));
        rejected("slot", prog(code(Op.PUSH, 1, Op.STORE, -1, Op.PUSH, 0, Op.RET), 1));
        assertEquals(1, Verifier.verify(prog(code(Op.LOAD, 0, Op.RET), 1))[0]);
    }

    @Test
    void callChecks() {
        Function two = new Function("f", 2, 2, code(Op.LOAD, 0, Op.RET));
        assertEquals(2, Verifier.verify(prog(code(Op.PUSH, 1, Op.PUSH, 2, Op.CALL, 1, 2, Op.PRINT,
            Op.PUSH, 0, Op.RET), 0, two))[0]);
        rejected("arity", prog(code(Op.PUSH, 1, Op.CALL, 1, 1, Op.PRINT, Op.PUSH, 0, Op.RET), 0, two));
        rejected("function", prog(code(Op.CALL, 7, 0, Op.PRINT, Op.PUSH, 0, Op.RET), 0, two));
        rejected("function", prog(code(Op.CALL, 0, 0, Op.PRINT, Op.PUSH, 0, Op.RET), 0, two));
    }

    @Test
    void retNeedsExactlyOneValue() {
        rejected("return", prog(code(Op.PUSH, 1, Op.PUSH, 2, Op.RET), 0));
    }

    @Test
    void fallingOffTheEnd() {
        rejected("falls off", prog(code(Op.PUSH, 1, Op.PRINT), 0));
        rejected("falls off", prog(code(), 0));
    }

    @Test
    void malformedCode() {
        rejected("opcode", prog(code(99, Op.RET), 0));
        rejected("truncated", prog(code(Op.PUSH), 0));
        rejected("truncated", prog(code(Op.PUSH, 0, Op.CALL, 1), 0));
    }

    @Test
    void mainMustTakeNoArguments() {
        Bytecode b = new Bytecode(List.of(new Function("main", 1, 1, GOOD)));
        rejected("main", b);
    }

    @Test
    void unreachableWellFormedCodeIsFine() {
        long[] c = code(Op.PUSH, 0, Op.RET, Op.POP);  // trailing POP is unreachable and well formed
        assertEquals(1, Verifier.verify(prog(c, 0))[0]);
    }

    @Test
    void compilerOutputAlwaysVerifies() {
        String[] srcs = {
            "print 1;", "let i = 0; while (i < 3) { i = i + 1; } print i;",
            "fn f(a, b) { if (a) { return b; } return f(1, 2) && 3 || 4; } print f(0, 1);",
            "fn g() { } g(); { let q = 1; q; } return;"};
        for (String s : srcs) {
            Verifier.verify(Lang.compile(s, false));
            Verifier.verify(Lang.compile(s, true));
        }
    }
}
