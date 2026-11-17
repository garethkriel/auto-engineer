package perf;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SudokuTest {
    static final String EASY =
        "530070000600195000098000060800060003400803001700020006060000280000419005000080079";
    static final String EASY_SOLUTION =
        "534678912672195348198342567859761423426853791713924856961537284287419635345286179";
    static final String HARD =
        "800000000003600000070090200050007000000045700000100030001000068008500010090000400";
    static final String EMPTY = ".".repeat(81);

    @Test
    void solvesEasyPuzzleToKnownSolution() {
        assertEquals(Optional.of(EASY_SOLUTION), Sudoku.solve(EASY));
    }

    @Test
    void solvedGridIsReturnedUnchanged() {
        assertEquals(Optional.of(EASY_SOLUTION), Sudoku.solve(EASY_SOLUTION));
        assertEquals(1, Sudoku.countSolutions(EASY_SOLUTION, 10));
    }

    @Test
    void dotAndZeroAreBothEmpty() {
        assertEquals(Sudoku.solve(EASY), Sudoku.solve(EASY.replace('0', '.')));
    }

    @Test
    void hardPuzzleSolvesQuicklyAndKeepsGivens() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String s = Sudoku.solve(HARD).orElseThrow();
            assertTrue(Sudoku.isValidSolution(s));
            for (int i = 0; i < 81; i++) {
                if (HARD.charAt(i) != '0') {
                    assertEquals(HARD.charAt(i), s.charAt(i), "given at " + i);
                }
            }
            assertEquals(1, Sudoku.countSolutions(HARD, 2));
        });
    }

    @Test
    void emptyGridHasManySolutionsCappedByLimit() {
        assertEquals(5, Sudoku.countSolutions(EMPTY, 5));
        assertEquals(1, Sudoku.countSolutions(EMPTY, 1));
        assertTrue(Sudoku.isValidSolution(Sudoku.solve(EMPTY).orElseThrow()));
    }

    @Test
    void noCandidateCellMeansNoSolution() {
        // row 0 holds 1..8, but column 8 already has a 9 -> cell (0,8) has no candidate
        String p = "12345678." + "00000000" + "9" + "0".repeat(63);
        assertEquals(81, p.length());
        assertEquals(Optional.empty(), Sudoku.solve(p));
        assertEquals(0, Sudoku.countSolutions(p, 3));
    }

    @Test
    void blankingAllOnesAndTwosGivesAtLeastTwoSolutions() {
        // swapping the digits 1 and 2 in a solution yields another valid grid
        String p = EASY_SOLUTION.replace('1', '.').replace('2', '.');
        assertEquals(2, Sudoku.countSolutions(p, 2));
        assertEquals(1, Sudoku.countSolutions(p, 1));
        String swapped = EASY_SOLUTION.replace('1', 'x').replace('2', '1').replace('x', '2');
        assertTrue(Sudoku.isValidSolution(swapped));
        assertTrue(Sudoku.countSolutions(p, 3) >= 2);
    }

    @Test
    void parseReturnsDigits() {
        int[] g = Sudoku.parse(EASY);
        assertEquals(81, g.length);
        assertEquals(5, g[0]);
        assertEquals(0, g[2]);
        assertEquals(9, g[80]);
    }

    @Test
    void parseRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Sudoku.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Sudoku.parse("123"));
        assertThrows(IllegalArgumentException.class, () -> Sudoku.parse("x" + EMPTY.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> Sudoku.parse(EMPTY + "."));
    }

    @Test
    void parseRejectsConflictingGivens() {
        String row = "55" + ".".repeat(79);
        String col = "5........" + "5" + ".".repeat(71);
        String box = "5........" + ".5" + ".".repeat(70);
        for (String p : new String[] {row, col, box}) {
            assertThrows(IllegalArgumentException.class, () -> Sudoku.parse(p), p);
            assertThrows(IllegalArgumentException.class, () -> Sudoku.solve(p), p);
        }
    }

    @Test
    void countRejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> Sudoku.countSolutions(EASY, 0));
    }

    @Test
    void isValidSolutionChecksEveryUnit() {
        assertTrue(Sudoku.isValidSolution(EASY_SOLUTION));
        assertFalse(Sudoku.isValidSolution(EASY));
        assertFalse(Sudoku.isValidSolution(null));
        assertFalse(Sudoku.isValidSolution("1".repeat(81)));
        // swap two cells in one row: rows stay valid, columns break
        char[] c = EASY_SOLUTION.toCharArray();
        char t = c[0];
        c[0] = c[1];
        c[1] = t;
        assertFalse(Sudoku.isValidSolution(new String(c)));
    }

    @Test
    void randomPuzzlesFromSolvedGridsStaySolvableAndConsistent() {
        Random rnd = new Random(42);
        for (int seed = 0; seed < 30; seed++) {
            char[] c = EASY_SOLUTION.toCharArray();
            for (int i = 0; i < 81; i++) {
                if (rnd.nextInt(100) < 55) {
                    c[i] = '.';
                }
            }
            String p = new String(c);
            String s = Sudoku.solve(p).orElseThrow();
            assertTrue(Sudoku.isValidSolution(s));
            for (int i = 0; i < 81; i++) {
                if (c[i] != '.') {
                    assertEquals(c[i], s.charAt(i));
                }
            }
            assertTrue(Sudoku.countSolutions(p, 3) >= 1);
        }
    }
}
