package ae.minilang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OptimizerTest {
    private static String opt(String src) {
        return Printer.source(Optimizer.optimize(Parser.parse(src))).trim();
    }

    @Test
    void foldsConstants() {
        assertEquals("print 7;", opt("print 1 + 2 * 3;"));
        assertEquals("print (-4);", opt("print 3 - 7;"));
        assertEquals("print 1;", opt("print !0 && 5 > 2;"));
        assertEquals("print 0;", opt("print !5 || 0 == 1;"));
    }

    @Test
    void neverFoldsAnErrorAway() {
        assertEquals("print (1 / 0);", opt("print 1 / 0;"));
        assertEquals("print (9223372036854775807 + 1);", opt("print 9223372036854775807 + 1;"));
        assertEquals("print (-(-9223372036854775808));", opt("print -(-9223372036854775808);"));
        assertEquals("print (5 % 0);", opt("print 5 % 0;"));
    }

    @Test
    void identitiesKeepTheOperandOnly() {
        assertEquals("let x = 5;\nprint (x + x);", opt("let x = 5; print (x * 1) + (0 + x);"));
        assertEquals("let x = 5;\nprint x;", opt("let x = 5; print (x - 0) / 1 + 0;"));
        assertEquals("let x = 5;\nprint (x % 1);", opt("let x = 5; print x % 1;"));
        // x * 0 may hide an overflow or a call inside x, so it must not collapse
        assertEquals("let x = 5;\nprint (x * 0);", opt("let x = 5; print x * 0;"));
        assertEquals("let x = 5;\nprint (-(-x));", opt("let x = 5; print -(-x);"));
    }

    @Test
    void shortCircuitFolding() {
        String f = "fn f() { print 9; return 1; } ";
        assertEquals("fn f() { print 9; return 1; }\nprint 0;", opt(f + "print 0 && f();"));
        assertEquals("fn f() { print 9; return 1; }\nprint 1;", opt(f + "print 1 || f();"));
        assertEquals("fn f() { print 9; return 1; }\nprint (f() != 0);", opt(f + "print 1 && f();"));
        assertEquals("fn f() { print 9; return 1; }\nprint (f() < 2);", opt(f + "print 0 || f() < 2;"));
        assertEquals("fn f() { print 9; return 1; }\nprint (f() && 0);", opt(f + "print f() && 0;"));
    }

    @Test
    void deadBranchesAndLoops() {
        assertEquals("print 2;", opt("if (0) { print 1; } else { print 2; }"));
        assertEquals("print 1;", opt("if (3 - 2) { print 1; } else { print 2; }"));
        assertEquals("{ let a = 1; print a; }", opt("if (1) { let a = 1; print a; }"));
        assertEquals("", opt("if (0) { print 1; }"));
        assertEquals("", opt("while (0) { print 1; }"));
        assertEquals("let i = 0;\nwhile (1) { i = (i + 1); }", opt("let i = 0; while (1) { i = i + 1; }"));
    }

    @Test
    void pureStatementsDisappearButEffectfulOnesStay() {
        String f = "fn f() { return 1; } ";
        assertEquals("fn f() { return 1; }", opt(f + "5; f;".replace("f;", "")));
        assertEquals("let x = 1;\nlet y = 2;", opt("let x = 1; x; let y = 2;"));
        assertEquals("let x = 1;\n(x + 1);", opt("let x = 1; x + 1;"));
        assertEquals("fn f() { return 1; }\nf();", opt(f + "f();"));
        assertEquals("fn f() { return 1; }\nf();", opt(f + "if (f()) { } else { }"));
    }

    @Test
    void codeAfterReturnIsRemoved() {
        assertEquals("fn f() { return 1; }", opt("fn f() { return 1; print 2; }"));
        assertEquals("return;", opt("return; print 1; let x = 2;"));
        assertEquals("fn f() { return 1; }", opt("fn f() { { return 1; } print 2; }"));
    }

    @Test
    void blocksKeepScopeOnlyWhenTheyDeclare() {
        assertEquals("print 1;", opt("{ print 1; }"));
        assertEquals("let x = 1;\n{ let x = 2; print x; }\nprint x;", opt("let x = 1; { let x = 2; print x; } print x;"));
        assertEquals("", opt("{ { } }"));
    }

    @Test
    void optimiserIsIdempotent() {
        String[] srcs = {
            "if (1) { if (0) { print 1; } else { print 2; } } print 0 || 1 && 5;",
            "let i = 0; while (i < 2 + 1) { { i = i + 1; } if (i == 9 - 8) { return; } }"};
        for (String s : srcs) {
            String once = opt(s);
            assertEquals(once, opt(once), s);
        }
    }
}
