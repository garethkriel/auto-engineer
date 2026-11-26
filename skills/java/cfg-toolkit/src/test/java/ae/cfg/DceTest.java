package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DceTest {
    @Test
    void removesDeadChainsAndUnreachableBlocks() {
        Function f = Function.parse("""
                func d(a, b)
                entry:
                  t = add a, b
                  dead1 = mul t, t
                  dead2 = add dead1, a
                  u = const 5
                  jmp next
                next:
                  ret t
                orphan:
                  ret a
                """);
        assertEquals("""
                func d(a, b)
                entry:
                  t = add a, b
                  jmp next
                next:
                  ret t
                """, Dce.run(f).toText());
    }

    @Test
    void overwrittenDefinitionIsDeadButTheLastOneStays() {
        Function f = Function.parse("func o(a)\nb:\n  x = const 1\n  x = const 2\n  ret x\n");
        assertEquals("func o(a)\nb:\n  x = const 2\n  ret x\n", Dce.run(f).toText());
    }

    @Test
    void definitionsLiveOnOnlyOneBranchSurvive() {
        Function f = Function.parse(Fixtures.DIAMOND);
        assertEquals(f, Dce.run(f));
    }

    @Test
    void loopCarriedSelfUseIsNotRemoved() {
        // Documented limitation: k is read only by its own update, but liveness cannot tell.
        Function f = Function.parse("""
                func l(n)
                entry:
                  k = const 0
                  i = const 0
                  one = const 1
                  jmp head
                head:
                  c = lt i, n
                  br c, body, exit
                body:
                  k = add k, one
                  i = add i, one
                  jmp head
                exit:
                  ret i
                """);
        assertEquals(f, Dce.run(f));
    }

    @Test
    void deadParametersAndTerminatorsStay() {
        Function f = Function.parse("func p(a, b)\nb0:\n  br a, b0, b1\nb1:\n  ret a\n");
        assertEquals(f, Dce.run(f));
    }

    @Test
    void idempotentAndSemanticsPreservingOnRandomPrograms() {
        Random rnd = new Random(424242L);
        int shrunk = 0;
        int comparisons = 0;
        for (int round = 0; round < 200; round++) {
            Function f = Function.parse(Gen.structured(rnd, 2, 5));
            Function g = Dce.run(f);
            assertEquals(g, Dce.run(g), "idempotent, round " + round);
            assertEquals(g, Function.parse(g.toText()));
            int before = count(f);
            int after = count(g);
            assertTrue(after <= before);
            shrunk += after < before ? 1 : 0;
            for (long[] args : Gen.argSets(rnd, 2, 6)) {
                Optional<Long> want = Interp.run(f, args, 20_000);
                assertTrue(want.isPresent(), "generator programs terminate; round " + round);
                assertEquals(want, Interp.run(g, args, 20_000), "round " + round + "\n" + f.toText() + "\n--\n" + g.toText());
                comparisons++;
            }
            // Nothing non-terminator that remains is dead right after it.
            Liveness lv = new Liveness(new Cfg(g));
            for (int b = 0; b < g.blocks().size(); b++) {
                List<Instr> ins = g.blocks().get(b).instrs();
                for (int i = 0; i < ins.size(); i++) {
                    final int bi = b;
                    final int ii = i;
                    ins.get(i).def().ifPresent(d -> assertTrue(lv.liveAfter(bi).get(ii).contains(d), "dead def left: " + ins.get(ii)));
                }
            }
        }
        assertTrue(shrunk > 100, "programs that shrank: " + shrunk);
        assertEquals(1200, comparisons);
    }

    private static int count(Function f) {
        return f.blocks().stream().mapToInt(b -> b.instrs().size()).sum();
    }
}
