package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import ae.cfg.Instr.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParseTest {
    @Test
    void parsesEveryInstructionKind() {
        Function f = Function.parse("""
                # a comment
                func k(a, b)

                start:   # entry
                  x = const -7
                  y = add a, b
                  z = sub x, y
                  w = mul z, z
                  l = lt w, a
                  e = eq l, l
                  c = copy e
                  br c, start, end
                end:
                  ret c
                """);
        assertEquals("k", f.name());
        assertEquals(List.of("a", "b"), f.params());
        assertEquals(2, f.blocks().size());
        assertEquals(List.of(
                new Const("x", -7), new Bin("y", Instr.Op.ADD, "a", "b"), new Bin("z", Instr.Op.SUB, "x", "y"),
                new Bin("w", Instr.Op.MUL, "z", "z"), new Bin("l", Instr.Op.LT, "w", "a"),
                new Bin("e", Instr.Op.EQ, "l", "l"), new Copy("c", "e"), new Br("c", "start", "end")),
                f.blocks().get(0).instrs());
        assertEquals(List.of(new Ret("c")), f.blocks().get(1).instrs());
    }

    @Test
    void defUsesAndTargets() {
        Instr bin = new Bin("d", Instr.Op.ADD, "p", "q");
        assertEquals(Optional.of("d"), bin.def());
        assertEquals(List.of("p", "q"), bin.uses());
        assertFalse(bin.isTerminator());
        assertEquals(Optional.of("d"), new Const("d", 1).def());
        assertEquals(List.of(), new Const("d", 1).uses());
        assertEquals(List.of("s"), new Copy("d", "s").uses());
        Instr br = new Br("c", "t", "e");
        assertTrue(br.isTerminator());
        assertEquals(Optional.empty(), br.def());
        assertEquals(List.of("c"), br.uses());
        assertEquals(List.of("t", "e"), br.targets());
        assertEquals(List.of("L"), new Jmp("L").targets());
        assertEquals(List.of("v"), new Ret("v").uses());
        assertEquals(List.of(), new Ret("v").targets());
        assertEquals(List.of(), bin.targets());
    }

    @Test
    void textRoundTrips() {
        for (String src : List.of(Fixtures.DIAMOND, Fixtures.LOOP, Fixtures.IRREDUCIBLE, Fixtures.UNREACHABLE,
                Fixtures.SELF_LOOP, Fixtures.SUM)) {
            Function f = Function.parse(src);
            assertEquals(f, Function.parse(f.toText()), src);
        }
        assertEquals("""
                func g(n)
                entry:
                  i = const 0
                  one = const 1
                  jmp head
                head:
                  c = lt i, n
                  br c, body, exit
                body:
                  i = add i, one
                  jmp head
                exit:
                  ret i
                """, Function.parse(Fixtures.LOOP).toText());
    }

    private static void rejects(String src, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Function.parse(src), src);
        assertTrue(e.getMessage().contains(messagePart), "message was: " + e.getMessage());
    }

    /** Each rejection has a control (the line below it in the same test) differing only by the defect. */
    @Test
    void rejectsMalformedPrograms() {
        String ok = "func f(a)\nb:\n  ret a\n";
        assertDoesNotThrow(() -> Function.parse(ok));
        rejects("func f(a)\nb:\n  x = const 1\n", "terminator");                 // missing terminator
        rejects("func f(a)\nb:\n  ret a\n  x = const 1\n  ret a\n", "terminator"); // instruction after terminator
        rejects("func f(a)\nb:\n  ret a\nb:\n  ret a\n", "duplicate label");
        rejects("func f(a)\nb:\n  jmp nowhere\n", "unknown label");
        rejects("func f(a, a)\nb:\n  ret a\n", "duplicate param");
        rejects("func f(a)\nb:\n  ret zz\n", "undefined variable zz");
        rejects("func f(a)\nb:\n  x = div a, a\n  ret x\n", "line 3");
        rejects("func f(a)\n  ret a\n", "line 2");                                  // instruction before first label
        rejects("func f(a)\n", "no blocks");
        rejects("func f(a)\nb:\nc:\n  ret a\n", "terminator");                     // empty block
        rejects("", "header");
        rejects("func f(a\nb:\n  ret a\n", "line 1");
        rejects("func f(a)\nb:\n  x = const 1.5\n  ret x\n", "line 3");
        rejects("func f(a)\nb:\n  x = add a\n  ret x\n", "line 3");
        rejects("func f(a)\nb:\n  br a, b\n", "line 3");
        rejects("func f(a)\nb:\n  1x = const 1\n  ret a\n", "line 3");
    }

    @Test
    void usedVariableMayBeDefinedLaterInTextOrOnOnePathOnly() {
        assertDoesNotThrow(() -> Function.parse("func f(a)\nb:\n  br a, c, d\nc:\n  x = const 1\n  jmp d\nd:\n  ret x\n"));
        assertDoesNotThrow(() -> Function.parse("func f(a)\nb:\n  jmp c\nc:\n  y = add x, a\n  jmp d\nd:\n  x = const 1\n  ret y\n"));
    }

    @Test
    void recordsCopyTheirLists() {
        List<Instr> instrs = new ArrayList<>(List.of(new Ret("a")));
        Block b = new Block("b", instrs);
        instrs.add(new Const("x", 1));
        assertEquals(1, b.instrs().size());
        assertThrows(UnsupportedOperationException.class, () -> b.instrs().add(new Ret("a")));

        List<String> params = new ArrayList<>(List.of("a"));
        List<Block> blocks = new ArrayList<>(List.of(b));
        Function f = new Function("f", params, blocks);
        params.add("zz");
        blocks.clear();
        assertEquals(List.of("a"), f.params());
        assertEquals(1, f.blocks().size());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> Function.parse(null));
        assertThrows(NullPointerException.class, () -> new Block(null, List.of(new Ret("a"))));
    }
}
