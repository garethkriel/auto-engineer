package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ae.minilang.Lang.Engine;
import ae.minilang.Lang.Result;
import ae.minilang.LangException.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-computed behaviour, run on every engine. */
class ExecutionTest {
    private static void ok(String src, Long... expected) {
        for (Engine e : Engine.values()) {
            Result r = Lang.run(src, e);
            assertNull(r.error(), e + " " + src);
            assertEquals(List.of(expected), r.output(), e + " " + src);
        }
    }

    private static void fails(String src, Kind kind, Long... printedBefore) {
        for (Engine e : Engine.values()) {
            Result r = Lang.run(src, e);
            assertEquals(kind, r.error(), e + " " + src);
            assertEquals(List.of(printedBefore), r.output(), e + " " + src);
        }
    }

    @Test
    void arithmeticAndPrecedence() {
        ok("print 1 + 2 * 3; print (1 + 2) * 3; print 10 - 4 - 3; print 7 / 2; print -7 / 2; print -7 % 2;",
            7L, 9L, 3L, 3L, -3L, -1L);
    }

    @Test
    void comparisonsAndLogicYieldZeroOrOne() {
        ok("print 3 < 4; print 4 < 3; print 3 <= 3; print 2 >= 3; print 5 == 5; print 5 != 5; print !0; print !7;"
            + " print 7 && 9; print 0 || 5; print 0 || 0; print 2 > 1 == 1;",
            1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L, 1L, 0L, 1L);
    }

    @Test
    void shortCircuitSkipsRightSide() {
        String f = "fn t(x) { print x; return x; } ";
        ok(f + "print 0 && t(1);", 0L);
        ok(f + "print 1 || t(2);", 1L);
        ok(f + "print 1 && t(3);", 3L, 1L);
        ok(f + "print 0 || t(4);", 4L, 1L);
        ok(f + "print 0 && 1/0; print 1 || 1/0;", 0L, 1L);
    }

    @Test
    void evaluationOrderIsLeftToRight() {
        String f = "fn t(x) { print x; return x; } fn g(a, b, c) { return a * 100 + b * 10 + c; } ";
        ok(f + "print g(t(1), t(2), t(3));", 1L, 2L, 3L, 123L);
        ok(f + "print t(1) + t(2) * t(3);", 1L, 2L, 3L, 7L);
        fails("fn t(x) { print x; return x; } print t(1) / (t(2) - 2) + t(9);", Kind.DIV_ZERO, 1L, 2L);
    }

    @Test
    void variablesAssignmentAndScopes() {
        ok("let x = 1; { let x = 2; print x; x = 3; print x; } print x;", 2L, 3L, 1L);
        ok("let x = 1; { x = 5; } print x;", 5L);
        ok("let x = 10; { let x = x + 1; print x; } print x;", 11L, 10L);
        ok("let i = 0; while (i < 3) { let sq = i * i; print sq; i = i + 1; }", 0L, 1L, 4L);
        ok("let x = 1; if (x) { let x = 7; print x; } else { print 0; } print x;", 7L, 1L);
    }

    @Test
    void ifElseChains() {
        String f = "fn sign(n) { if (n < 0) { return -1; } else if (n == 0) { return 0; } else { return 1; } } ";
        ok(f + "print sign(-5); print sign(0); print sign(9);", -1L, 0L, 1L);
    }

    @Test
    void whileLoopSum() {
        ok("let i = 1; let s = 0; while (i <= 100) { s = s + i; i = i + 1; } print s;", 5050L);
    }

    @Test
    void recursionFibAndFactorial() {
        String fib = "fn fib(n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); } ";
        ok(fib + "print fib(0); print fib(1); print fib(10); print fib(15);", 0L, 1L, 55L, 610L);
        String fact = "fn fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } ";
        ok(fact + "print fact(20);", 2432902008176640000L);
        fails(fact + "print fact(5); print fact(21);", Kind.OVERFLOW, 120L);
    }

    @Test
    void mutualRecursionAndParameterIsolation() {
        ok("fn even(n) { if (n == 0) { return 1; } return odd(n - 1); }"
            + "fn odd(n) { if (n == 0) { return 0; } return even(n - 1); }"
            + "print even(10); print odd(7); print even(7);", 1L, 1L, 0L);
        ok("fn bump(a) { a = a + 1; return a; } let x = 5; print bump(x); print x;", 6L, 5L);
    }

    @Test
    void fallingOffTheEndAndBareReturnYieldZero() {
        ok("fn a() { } fn b() { return; } fn c() { let z = 9; } print a(); print b(); print c();", 0L, 0L, 0L);
    }

    @Test
    void topLevelReturnHaltsProgram() {
        ok("print 1; return; print 2;", 1L);
        ok("print 1; if (1) { return 5; } print 2;", 1L);
        ok("let i = 0; while (1) { i = i + 1; if (i == 3) { return; } print i; }", 1L, 2L);
    }

    @Test
    void returnInsideLoopLeavesFunction() {
        ok("fn find(n) { let i = 0; while (i < 100) { if (i * i >= n) { return i; } i = i + 1; } return -1; }"
            + "print find(50); print find(10000);", 8L, -1L);
    }

    @Test
    void arithmeticErrorsKeepEarlierOutput() {
        fails("print 1; print 5 / 0; print 2;", Kind.DIV_ZERO, 1L);
        fails("print 1; print 5 % (3 - 3);", Kind.DIV_ZERO, 1L);
        fails("print 9223372036854775807; print 9223372036854775807 + 1;", Kind.OVERFLOW, Long.MAX_VALUE);
        fails("print -(-9223372036854775808);", Kind.OVERFLOW);
        fails("print (-9223372036854775808) / (0 - 1);", Kind.OVERFLOW);
        ok("print -9223372036854775808; print (-9223372036854775808) % (0 - 1);", Long.MIN_VALUE, 0L);
    }

    @Test
    void callDepthLimitIsExactlyOneHundredFrames() {
        String d = "fn d(n) { if (n == 0) { return 0; } return d(n - 1) + 1; } ";
        ok(d + "print d(98);", 98L);
        fails(d + "print 7; print d(99);", Kind.STACK_OVERFLOW, 7L);
        fails("fn r(n) { return r(n + 1); } r(0);", Kind.STACK_OVERFLOW);
    }

    @Test
    void stepBudgetStopsRunawayLoops() {
        for (Engine e : Engine.values()) {
            Result r = Lang.run("let i = 0; while (1) { i = i + 1; }", e, 10_000);
            assertEquals(Kind.STEP_LIMIT, r.error(), e.toString());
        }
        for (Engine e : Engine.values()) {
            Result r = Lang.run("let i = 0; while (i < 50) { i = i + 1; } print i;", e, 10_000);
            assertEquals(List.of(50L), r.output(), e.toString());
        }
    }

    @Test
    void staticErrorsProduceNoOutput() {
        for (Engine e : Engine.values()) {
            assertEquals(Kind.NAME, Lang.run("print 1; print zz;", e).error());
            assertEquals(List.of(), Lang.run("print 1; print zz;", e).output());
            assertEquals(Kind.SYNTAX, Lang.run("print 1 +;", e).error());
            assertEquals(Kind.ARITY, Lang.run("fn f(a) { return a; } print f();", e).error());
        }
    }
}
