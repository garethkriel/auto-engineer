package ae.life;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashLifeTest {
    static Universe fromRle(String rle) {
        return Universe.of(Rle.parse(rle).cells());
    }

    static final String GLIDER = "x = 3, y = 3\nbo$2bo$3o!";

    @Test
    void blinkerHasPeriodTwo() {
        Universe u = Universe.of(List.of(new Cell(-1, 0), new Cell(0, 0), new Cell(1, 0)));
        u.step(1);
        assertEquals(List.of(new Cell(0, -1), new Cell(0, 0), new Cell(0, 1)), u.cells());
        u.step(1);
        assertEquals(List.of(new Cell(-1, 0), new Cell(0, 0), new Cell(1, 0)), u.cells());
        assertEquals(2, u.generation());
    }

    @Test
    void blockIsStillAndEmptyStaysEmptyEvenForHugeSteps() {
        Universe block = Universe.of(List.of(new Cell(0, 0), new Cell(1, 0), new Cell(0, 1), new Cell(1, 1)));
        block.step(1L << 50);
        assertEquals(4, block.population());
        assertEquals(1L << 50, block.generation());
        Universe empty = new Universe();
        empty.step((1L << 55) + 12345);
        assertEquals(0, empty.population());
        assertEquals((1L << 55) + 12345, empty.generation());
    }

    @Test
    void stepZeroChangesNothingAndNegativeIsRejected() {
        Universe u = fromRle(GLIDER);
        List<Cell> before = u.cells();
        u.step(0);
        assertEquals(before, u.cells());
        assertEquals(0, u.generation());
        assertThrows(IllegalArgumentException.class, () -> u.step(-1));
    }

    @Test
    void gliderTravelsOneCellDiagonallyPerFourGenerations() {
        List<Cell> start = Rle.parse(GLIDER).cells();
        for (int m = 1; m <= 12; m++) {
            Universe u = Universe.of(start);
            u.step(4L * m);
            List<Cell> expected = new ArrayList<>();
            for (Cell c : start) {
                expected.add(new Cell(c.x() + m, c.y() + m));
            }
            assertEquals(expected, u.cells(), "after " + 4 * m + " generations");
        }
    }

    @Test
    void gliderAfterTwoToTheFortyGenerationsIsExactlyShifted() {
        List<Cell> start = Rle.parse(GLIDER).cells();
        long shift = 1L << 38; // 2^40 / 4
        Universe u = Universe.of(start);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> u.step(1L << 40));
        List<Cell> expected = new ArrayList<>();
        for (Cell c : start) {
            expected.add(new Cell(c.x() + shift, c.y() + shift));
        }
        assertEquals(expected, u.cells());
        assertEquals(1L << 40, u.generation());
        assertTrue(u.nodeCount() < 5000, "hash-consing should keep this tiny, got " + u.nodeCount());
        u.checkInvariants();
    }

    @Test
    void rPentominoHasOneHundredSixteenCellsAtGeneration1103() {
        Universe u = fromRle("x = 3, y = 3\nb2o$2ob$bo!");
        u.step(1103);
        assertEquals(116, u.population());
        assertEquals(1103, u.generation());
        // one generation earlier it is still evolving: population differs.
        Universe v = fromRle("x = 3, y = 3\nb2o$2ob$bo!");
        v.step(1102);
        assertNotEquals(116, v.population());
    }

    @Test
    void gosperGunEmitsOneGliderEveryThirtyGenerations() {
        Universe u = fromRle(RleTest.GUN);
        assertEquals(36, u.population());
        for (int k = 1; k <= 20; k++) {
            u.step(30);
            assertEquals(36 + 5L * k, u.population(), "generation " + 30 * k);
        }
    }

    @Test
    void gosperGunAfterMillionGenerationsHasExactGliderCount() {
        Universe u = fromRle(RleTest.GUN);
        long gens = 30L * 32768; // 983040 generations, 32768 gliders
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> u.step(gens));
        assertEquals(36 + 5L * 32768, u.population());
        u.checkInvariants();
    }

    @Test
    void setGetAndFarAwayCells() {
        Universe u = new Universe();
        assertFalse(u.get(5, 5));
        u.set(5, 5, true);
        u.set(-1L << 40, 1L << 41, true);
        assertTrue(u.get(5, 5));
        assertTrue(u.get(-1L << 40, 1L << 41));
        assertFalse(u.get(5, 6));
        assertFalse(u.get(1L << 50, 0));
        assertEquals(2, u.population());
        u.set(5, 5, false);
        assertEquals(1, u.population());
        assertEquals(List.of(new Cell(-1L << 40, 1L << 41)), u.cells());
        u.set(5, 5, false); // clearing a dead cell is a no-op
        assertEquals(1, u.population());
        u.checkInvariants();
    }

    @Test
    void cellsAreSortedByRowThenColumn() {
        Universe u = Universe.of(List.of(new Cell(3, 2), new Cell(-4, 2), new Cell(9, -1), new Cell(0, 7)));
        assertEquals(List.of(new Cell(9, -1), new Cell(-4, 2), new Cell(3, 2), new Cell(0, 7)), u.cells());
    }

    @Test
    void lonelyCellsDieAndUnderpopulationIsExact() {
        Universe u = Universe.of(List.of(new Cell(0, 0), new Cell(1, 0)));
        u.step(1);
        assertEquals(0, u.population());
    }

    @Test
    void thereIsNoWrapAroundAtAnyScale() {
        // A blinker at x = 2^30 must stay a blinker (no torus effects after expansions).
        long x = 1L << 30;
        Universe u = Universe.of(List.of(new Cell(x - 1, 5), new Cell(x, 5), new Cell(x + 1, 5)));
        u.step(1_000_001);
        assertEquals(List.of(new Cell(x, 4), new Cell(x, 5), new Cell(x, 6)), u.cells());
    }
}
