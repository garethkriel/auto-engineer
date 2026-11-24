package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded random edit sequences against a StringBuilder oracle, with the invariant checker after every step. */
class RopeDifferentialTest {

    private static String randomText(Random rnd, int maxLen) {
        int n = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = rnd.nextInt(10);
            sb.append(c < 2 ? '\n' : (char) ('a' + c));
        }
        return sb.toString();
    }

    private static void assertSameLines(Rope r, String s, String ctx) {
        int newlines = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                newlines++;
            }
        }
        assertEquals(newlines + 1, r.lineCount(), ctx);
        int start = 0;
        for (int k = 0; k <= newlines; k++) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? s.length() : nl;
            assertEquals(start, r.lineStart(k), ctx + " lineStart " + k);
            assertEquals(s.substring(start, end), r.line(k), ctx + " line " + k);
            start = end + 1;
        }
    }

    @Test
    void randomEditsMatchStringBuilder() {
        int[] opCounts = new int[6];
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            StringBuilder oracle = new StringBuilder(randomText(rnd, 150));
            Rope rope = Rope.of(oracle);
            for (int step = 0; step < 250; step++) {
                int len = oracle.length();
                int op = rnd.nextInt(6);
                if (len > 1500) {
                    op = 1; // keep sizes bounded: force a deletion
                }
                String ctx = "seed " + seed + " step " + step + " op " + op;
                switch (op) {
                    case 0 -> {
                        int at = rnd.nextInt(len + 1);
                        String ins = randomText(rnd, rnd.nextInt(5) == 0 ? 120 : 6);
                        rope = rope.insert(at, ins);
                        oracle.insert(at, ins);
                    }
                    case 1 -> {
                        int a = rnd.nextInt(len + 1);
                        int b = a + rnd.nextInt(Math.min(len - a, len > 1500 ? 600 : 60) + 1);
                        rope = rope.delete(a, b);
                        oracle.delete(a, b);
                    }
                    case 2 -> {
                        int a = rnd.nextInt(len + 1);
                        int b = a + rnd.nextInt(Math.min(len - a, 40) + 1);
                        String rep = randomText(rnd, 50);
                        rope = rope.replace(a, b, rep);
                        oracle.replace(a, b, rep);
                    }
                    case 3 -> {
                        Rope other = Rope.of(randomText(rnd, 90));
                        if (rnd.nextBoolean()) {
                            rope = rope.concat(other);
                            oracle.append(other.toString());
                        } else {
                            rope = other.concat(rope);
                            oracle.insert(0, other.toString());
                        }
                    }
                    case 4 -> { // rotate: split at i, swap halves
                        int at = rnd.nextInt(len + 1);
                        Rope.Split s = rope.split(at);
                        rope = s.right().concat(s.left());
                        String all = oracle.toString();
                        oracle = new StringBuilder(all.substring(at) + all.substring(0, at));
                    }
                    default -> { // keep a slice
                        int a = rnd.nextInt(len + 1);
                        int b = a + rnd.nextInt(len - a + 1);
                        if (b - a < 20 && len > 20) {
                            break;
                        }
                        rope = rope.subSequence(a, b);
                        oracle = new StringBuilder(oracle.substring(a, b));
                    }
                }
                opCounts[op]++;
                rope.check();
                assertEquals(oracle.toString(), rope.toString(), ctx);
                assertEquals(oracle.length(), rope.length(), ctx);
                if (step % 10 == 0) {
                    assertSameLines(rope, oracle.toString(), ctx);
                    if (oracle.length() > 0) {
                        int i = rnd.nextInt(oracle.length());
                        assertEquals(oracle.charAt(i), rope.charAt(i), ctx);
                    }
                    int off = rnd.nextInt(oracle.length() + 1);
                    String s = oracle.toString();
                    int line = (int) s.substring(0, off).chars().filter(c -> c == '\n').count();
                    assertEquals(line, rope.lineOf(off), ctx);
                    Rope.Position p = rope.positionOf(off);
                    assertEquals(off, rope.offsetOf(p.line(), p.column()), ctx);
                }
            }
        }
        for (int op = 0; op < 5; op++) {
            assertTrue(opCounts[op] >= 400, "operation " + op + " ran only " + opCounts[op] + " times");
        }
    }

    @Test
    void persistenceUnderRandomEdits() {
        Random rnd = new Random(99);
        String base = randomText(rnd, 400) + "tail".repeat(10);
        Rope[] versions = new Rope[60];
        String[] expected = new String[60];
        versions[0] = Rope.of(base);
        expected[0] = base;
        for (int v = 1; v < versions.length; v++) {
            int parent = rnd.nextInt(v);
            String p = expected[parent];
            int a = rnd.nextInt(p.length() + 1);
            int b = a + rnd.nextInt(Math.min(p.length() - a, 50) + 1);
            String ins = randomText(rnd, 30);
            versions[v] = versions[parent].replace(a, b, ins);
            expected[v] = p.substring(0, a) + ins + p.substring(b);
        }
        for (int v = 0; v < versions.length; v++) {
            versions[v].check();
            assertEquals(expected[v], versions[v].toString(), "version " + v);
        }
    }
}
