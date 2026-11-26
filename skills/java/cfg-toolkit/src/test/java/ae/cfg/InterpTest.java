package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterpTest {
    @Test
    void sumLoop() {
        Function f = Function.parse(Fixtures.SUM);
        assertEquals(Optional.of(10L), Interp.run(f, new long[] {4}, 1000));
        assertEquals(Optional.of(0L), Interp.run(f, new long[] {0}, 1000));
        assertEquals(Optional.of(0L), Interp.run(f, new long[] {-3}, 1000));
        assertEquals(Optional.of(5050L), Interp.run(f, new long[] {100}, 10_000));
    }

    @Test
    void stepBudgetIsExact() {
        // n = -1: entry(4 instrs) + head(2: c = lt n,i is true at once) + exit(1) = 7 instructions.
        Function f = Function.parse(Fixtures.SUM);
        assertEquals(Optional.of(0L), Interp.run(f, new long[] {-1}, 7));
        assertEquals(Optional.empty(), Interp.run(f, new long[] {-1}, 6));
        // n = 0 takes one trip through the body: 4 + 2 + 3 (body) + 2 + 1 = 12.
        assertEquals(Optional.of(0L), Interp.run(f, new long[] {0}, 12));
        assertEquals(Optional.empty(), Interp.run(f, new long[] {0}, 11));
        Function inf = Function.parse("func i()\nb:\n  jmp b\n");
        assertEquals(Optional.empty(), Interp.run(inf, new long[0], 1000));
    }

    @Test
    void arithmeticWrapsAndComparesGiveOneOrZero() {
        Function f = Function.parse("""
                func w(a)
                b:
                  m = const 9223372036854775807
                  s = add m, a
                  t = sub s, a
                  u = mul a, a
                  l = lt s, m
                  q = eq t, m
                  r = add l, q
                  v = add r, u
                  ret v
                """);
        // a = 1: s wraps to MIN, so lt(s,m)=1; t = MIN-1 wraps back to MAX so eq=1; u=1; v = 1+1+1.
        assertEquals(Optional.of(3L), Interp.run(f, new long[] {1}, 100));
        // a = 0: s = m, lt = 0, t = m so eq = 1, u = 0 -> 1.
        assertEquals(Optional.of(1L), Interp.run(f, new long[] {0}, 100));
    }

    @Test
    void readingAnUnassignedVariableFails() {
        Function f = Function.parse("func u(a)\nentry:\n  br a, l, r\nl:\n  x = const 1\n  jmp r\nr:\n  ret x\n");
        assertEquals(Optional.of(1L), Interp.run(f, new long[] {1}, 100));
        assertThrows(IllegalStateException.class, () -> Interp.run(f, new long[] {0}, 100));
    }

    @Test
    void wrongArgumentCountFails() {
        Function f = Function.parse(Fixtures.SUM);
        assertThrows(IllegalArgumentException.class, () -> Interp.run(f, new long[0], 10));
        assertThrows(IllegalArgumentException.class, () -> Interp.run(f, new long[] {1, 2}, 10));
    }
}
