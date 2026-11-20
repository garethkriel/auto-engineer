package ae.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AssignmentTest {

    @Test
    void square3x3() {
        Assignment.Result r = Assignment.solve(new long[][] {{4, 1, 3}, {2, 0, 5}, {3, 2, 2}});
        assertEquals(5, r.totalCost());
        assertArrayEquals(new int[] {1, 0, 2}, r.columnOfRow());
    }

    @Test
    void rectangularMoreColumnsThanRows() {
        Assignment.Result r = Assignment.solve(new long[][] {{9, 2, 7, 8}, {6, 4, 3, 7}});
        assertEquals(5, r.totalCost());
        assertArrayEquals(new int[] {1, 2}, r.columnOfRow());
    }

    @Test
    void negativeCosts() {
        Assignment.Result r = Assignment.solve(new long[][] {{-5, 0}, {0, -5}});
        assertEquals(-10, r.totalCost());
        assertArrayEquals(new int[] {0, 1}, r.columnOfRow());
    }

    @Test
    void emptyMatrix() {
        Assignment.Result r = Assignment.solve(new long[0][0]);
        assertEquals(0, r.totalCost());
        assertEquals(0, r.columnOfRow().length);
    }

    @Test
    void rejectsBadShapes() {
        assertThrows(IllegalArgumentException.class, () -> Assignment.solve(new long[][] {{1}, {2}}));
        assertThrows(IllegalArgumentException.class, () -> Assignment.solve(new long[][] {{1, 2}, {3}}));
        assertThrows(IllegalArgumentException.class, () -> Assignment.solve(new long[][] {{Assignment.MAX_ABS_COST + 1}}));
        assertThrows(NullPointerException.class, () -> Assignment.solve(null));
    }

    private static long brute(long[][] c, int row, boolean[] used) {
        if (row == c.length) {
            return 0;
        }
        long best = Long.MAX_VALUE;
        for (int j = 0; j < used.length; j++) {
            if (!used[j]) {
                used[j] = true;
                best = Math.min(best, c[row][j] + brute(c, row + 1, used));
                used[j] = false;
            }
        }
        return best;
    }

    @Test
    void randomMatchesPermutationBruteForceAndIsAValidAssignment() {
        Random rnd = new Random(21);
        for (int trial = 0; trial < 300; trial++) {
            int n = 1 + rnd.nextInt(5);
            int m = n + rnd.nextInt(3);
            long[][] c = new long[n][m];
            for (long[] row : c) {
                for (int j = 0; j < m; j++) {
                    row[j] = rnd.nextInt(41) - 20;
                }
            }
            Assignment.Result r = Assignment.solve(c);
            String msg = "trial " + trial;
            assertEquals(brute(c, 0, new boolean[m]), r.totalCost(), msg);
            boolean[] used = new boolean[m];
            long sum = 0;
            for (int i = 0; i < n; i++) {
                int j = r.columnOfRow()[i];
                assertTrue(j >= 0 && j < m && !used[j], msg);
                used[j] = true;
                sum += c[i][j];
            }
            assertEquals(sum, r.totalCost(), msg);
        }
    }

    @Test
    void agreesWithMinCostFlow() {
        Random rnd = new Random(22);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rnd.nextInt(6);
            int m = n + rnd.nextInt(3);
            long[][] c = new long[n][m];
            FlowNetwork g = new FlowNetwork(n + m + 2);
            for (int i = 0; i < n; i++) {
                g.addEdge(0, 1 + i, 1, 0);
                for (int j = 0; j < m; j++) {
                    c[i][j] = rnd.nextInt(1001) - 500;
                    g.addEdge(1 + i, 1 + n + j, 1, c[i][j]);
                }
            }
            for (int j = 0; j < m; j++) {
                g.addEdge(1 + n + j, n + m + 1, 1, 0);
            }
            FlowNetwork.FlowResult fr = g.minCostFlow(0, n + m + 1, n);
            assertEquals(n, fr.flow());
            assertEquals(fr.cost(), Assignment.solve(c).totalCost(), "trial " + trial);
        }
    }

    @Test
    void largeMatrixIsFast() {
        int n = 300;
        Random rnd = new Random(23);
        long[][] c = new long[n][n];
        for (long[] row : c) {
            for (int j = 0; j < n; j++) {
                row[j] = rnd.nextInt(1_000_000);
            }
        }
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Assignment.solve(c));
    }
}
