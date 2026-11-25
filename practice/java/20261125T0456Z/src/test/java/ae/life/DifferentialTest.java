package ae.life;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DifferentialTest {
    static List<Cell> soup(Random rnd, int size, double density) {
        List<Cell> cells = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (rnd.nextDouble() < density) {
                    cells.add(new Cell(x - size / 2, y - size / 2));
                }
            }
        }
        return cells;
    }

    @Test
    void randomSoupsAgreeWithNaiveAfterEveryRandomStep() {
        for (int seed = 0; seed < 60; seed++) {
            Random rnd = new Random(seed);
            List<Cell> start = soup(rnd, 4 + rnd.nextInt(14), 0.2 + 0.3 * rnd.nextDouble());
            Universe u = Universe.of(start);
            NaiveLife naive = new NaiveLife(start);
            for (int round = 0; round < 6; round++) {
                long n = rnd.nextInt(41);
                u.step(n);
                naive.step(n);
                assertEquals(naive.cells(), u.cells(), "seed " + seed + " round " + round);
                assertEquals(naive.population(), u.population());
                assertEquals(naive.generation(), u.generation());
                u.checkInvariants();
            }
        }
    }

    @Test
    void longRunsOfSmallSoupsAgree() {
        for (int seed = 100; seed < 106; seed++) {
            Random rnd = new Random(seed);
            List<Cell> start = soup(rnd, 8, 0.45);
            Universe u = Universe.of(start);
            NaiveLife naive = new NaiveLife(start);
            long n = 150 + rnd.nextInt(150);
            u.step(n);
            naive.step(n);
            assertEquals(naive.cells(), u.cells(), "seed " + seed + " n " + n);
        }
    }

    @Test
    void stepIsAdditiveInTheNumberOfGenerations() {
        for (int seed = 200; seed < 230; seed++) {
            Random rnd = new Random(seed);
            List<Cell> start = soup(rnd, 10, 0.4);
            long a = rnd.nextInt(70);
            long b = rnd.nextInt(70);
            Universe split = Universe.of(start);
            split.step(a);
            split.step(b);
            Universe whole = Universe.of(start);
            whole.step(a + b);
            assertEquals(whole.cells(), split.cells(), "seed " + seed + " a=" + a + " b=" + b);
            assertEquals(a + b, split.generation());
        }
    }

    @Test
    void everyPowerOfTwoStepMatchesNaive() {
        Random rnd = new Random(5);
        List<Cell> start = soup(rnd, 9, 0.4);
        for (int j = 0; j <= 8; j++) {
            Universe u = Universe.of(start);
            NaiveLife naive = new NaiveLife(start);
            u.step(1L << j);
            naive.step(1L << j);
            assertEquals(naive.cells(), u.cells(), "2^" + j);
        }
    }

    @Test
    void naiveOracleItselfKnowsTheBlinkerAndTheGlider() {
        NaiveLife blinker = new NaiveLife(List.of(new Cell(-1, 0), new Cell(0, 0), new Cell(1, 0)));
        blinker.step(1);
        assertEquals(List.of(new Cell(0, -1), new Cell(0, 0), new Cell(0, 1)), blinker.cells());
        NaiveLife glider = new NaiveLife(Rle.parse("x = 3, y = 3\nbo$2bo$3o!").cells());
        glider.step(40);
        // 40 generations = 10 diagonal steps: (1,0),(2,1),(0,2),(1,2),(2,2) shifted by (10,10)
        assertEquals(List.of(new Cell(11, 10), new Cell(12, 11), new Cell(10, 12), new Cell(11, 12), new Cell(12, 12)),
                glider.cells());
    }

    @Test
    void patternsNearTheEdgeOfTheInitialRootAreNeverClipped() {
        // Initial root is level 3 = [-4,4)^2. A blinker at every position, then 1..4 generations: growth beyond
        // the root edge must trigger an expansion rather than being cut off.
        for (int y = -4; y < 4; y++) {
            for (int x = -4; x < 3; x++) {
                List<Cell> start = List.of(new Cell(x, y), new Cell(x + 1, y), new Cell(x + 2 > 3 ? x : x + 2, y == 3 ? 2 : y));
                for (long n = 1; n <= 4; n++) {
                    Universe u = Universe.of(start);
                    NaiveLife naive = new NaiveLife(start);
                    u.step(n);
                    naive.step(n);
                    assertEquals(naive.cells(), u.cells(), "start " + start + " n " + n);
                }
            }
        }
    }
}
