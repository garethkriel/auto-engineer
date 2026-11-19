package ae.sat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DimacsTest {

    @Test
    void parsesCommentsHeaderAndMultiLineClauses() {
        String text = "c hello\np cnf 3 2\n1 -2\n3 0 -1\n2 0\n";
        Cnf cnf = Dimacs.parse(text);
        assertEquals(3, cnf.numVars());
        assertEquals(2, cnf.clauses().size());
        assertArrayEquals(new int[] {1, -2, 3}, cnf.clauses().get(0));
        assertArrayEquals(new int[] {-1, 2}, cnf.clauses().get(1));
    }

    @Test
    void percentLineEndsInput() {
        Cnf cnf = Dimacs.parse("p cnf 2 1\n1 2 0\n%\n0\n");
        assertEquals(1, cnf.clauses().size());
    }

    @Test
    void writeThenParseRoundTrips() {
        Cnf cnf = new Cnf(4, List.of(new int[] {1, -4}, new int[] {}, new int[] {2, 3, -1}));
        String text = Dimacs.write(cnf);
        assertEquals("p cnf 4 3\n1 -4 0\n0\n2 3 -1 0\n", text);
        Cnf back = Dimacs.parse(text);
        assertEquals(4, back.numVars());
        assertEquals(3, back.clauses().size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(cnf.clauses().get(i), back.clauses().get(i));
        }
    }

    @Test
    void errorsCarryLineNumbers() {
        assertEquals("line 1: missing header", msg("1 2 0\n"));
        assertEquals("line 2: duplicate header", msg("p cnf 1 1\np cnf 1 1\n"));
        assertEquals("line 1: malformed header", msg("p cnf x 1\n"));
        assertEquals("line 2: literal 5 out of range", msg("p cnf 2 1\n1 5 0\n"));
        assertEquals("line 2: bad token 'a'", msg("p cnf 2 1\n1 a 0\n"));
        assertEquals("line 2: unterminated clause", msg("p cnf 2 1\n1 2\n"));
        assertEquals("line 2: expected 2 clauses but found 1", msg("p cnf 2 2\n1 0\n"));
        assertEquals("line 2: too many clauses", msg("p cnf 2 1\n1 0 2 0\n"));
    }

    @Test
    void cnfRejectsBadLiteralsAndCopiesInput() {
        assertThrows(IllegalArgumentException.class, () -> new Cnf(2, List.of(new int[] {0})));
        assertThrows(IllegalArgumentException.class, () -> new Cnf(2, List.of(new int[] {-3})));
        assertThrows(IllegalArgumentException.class, () -> new Cnf(-1, List.of()));
        int[] c = {1, 2};
        Cnf cnf = new Cnf(2, List.of(c));
        c[0] = 2;
        assertArrayEquals(new int[] {1, 2}, cnf.clauses().get(0));
        cnf.clauses().get(0)[1] = 1;
        assertArrayEquals(new int[] {1, 2}, cnf.clauses().get(0), "accessor must hand out copies");
    }

    private static String msg(String text) {
        return assertThrows(IllegalArgumentException.class, () -> Dimacs.parse(text)).getMessage();
    }
}
