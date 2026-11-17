package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BacktrackTest {

    @Test
    void permutationsOfDistinctValuesAreLexicographic() {
        assertEquals(
                List.of(List.of(1, 2, 3), List.of(1, 3, 2), List.of(2, 1, 3),
                        List.of(2, 3, 1), List.of(3, 1, 2), List.of(3, 2, 1)),
                Backtrack.uniquePermutations(new int[] {3, 1, 2}));
    }

    @Test
    void permutationsSkipDuplicates() {
        assertEquals(
                List.of(List.of(1, 1, 2), List.of(1, 2, 1), List.of(2, 1, 1)),
                Backtrack.uniquePermutations(new int[] {1, 2, 1}));
    }

    @Test
    void permutationsHandleNegativesAndExtremes() {
        assertEquals(
                List.of(List.of(Integer.MIN_VALUE, Integer.MAX_VALUE),
                        List.of(Integer.MAX_VALUE, Integer.MIN_VALUE)),
                Backtrack.uniquePermutations(new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE}));
    }

    @Test
    void permutationsOfEmptyIsSingleEmpty() {
        assertEquals(List.of(List.of()), Backtrack.uniquePermutations(new int[0]));
    }

    @Test
    void permutationsDoNotMutateInput() {
        int[] in = {2, 1, 2};
        Backtrack.uniquePermutations(in);
        assertArrayEquals(new int[] {2, 1, 2}, in);
    }

    @Test
    void permutationsRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> Backtrack.uniquePermutations(null));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,0", "3,0", "4,2", "5,10", "6,4", "7,40", "8,92"})
    void queenCounts(int n, int expected) {
        assertEquals(expected, Backtrack.nQueens(n).size());
    }

    @Test
    void fourQueensExactSolutions() {
        List<int[]> sols = Backtrack.nQueens(4);
        assertEquals(2, sols.size());
        assertArrayEquals(new int[] {1, 3, 0, 2}, sols.get(0));
        assertArrayEquals(new int[] {2, 0, 3, 1}, sols.get(1));
    }

    @Test
    void everyEightQueensSolutionIsValidAndOrdered() {
        List<int[]> sols = Backtrack.nQueens(8);
        for (int s = 0; s < sols.size(); s++) {
            int[] q = sols.get(s);
            for (int i = 0; i < 8; i++) {
                for (int j = i + 1; j < 8; j++) {
                    assertNotEquals(q[i], q[j]);
                    assertNotEquals(j - i, Math.abs(q[i] - q[j]));
                }
            }
            if (s > 0) {
                assertTrue(Arrays.compare(sols.get(s - 1), q) < 0);
            }
        }
    }

    @Test
    void queensRejectNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> Backtrack.nQueens(0));
        assertThrows(IllegalArgumentException.class, () -> Backtrack.nQueens(-3));
    }

    @Test
    void combinationSumClassicExample() {
        assertEquals(List.of(List.of(2, 2, 3), List.of(7)),
                Backtrack.combinationSum(new int[] {2, 3, 6, 7}, 7));
    }

    @Test
    void combinationSumIgnoresDuplicateCandidatesAndOrder() {
        assertEquals(List.of(List.of(1, 1, 1), List.of(1, 2), List.of(3)),
                Backtrack.combinationSum(new int[] {3, 1, 2, 1}, 3));
    }

    @Test
    void combinationSumNoSolutionAndZeroTarget() {
        assertEquals(List.of(), Backtrack.combinationSum(new int[] {5, 7}, 3));
        assertEquals(List.of(List.of()), Backtrack.combinationSum(new int[] {5}, 0));
        assertEquals(List.of(List.of()), Backtrack.combinationSum(new int[0], 0));
    }

    @Test
    void combinationSumLargeCandidatesDoNotOverflow() {
        assertEquals(List.of(), Backtrack.combinationSum(new int[] {Integer.MAX_VALUE}, 5));
        assertEquals(List.of(List.of(Integer.MAX_VALUE)),
                Backtrack.combinationSum(new int[] {Integer.MAX_VALUE, 1_500_000_000}, Integer.MAX_VALUE));
    }

    @Test
    void combinationSumValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> Backtrack.combinationSum(null, 3));
        assertThrows(IllegalArgumentException.class, () -> Backtrack.combinationSum(new int[] {0}, 3));
        assertThrows(IllegalArgumentException.class, () -> Backtrack.combinationSum(new int[] {-1}, 3));
        assertThrows(IllegalArgumentException.class, () -> Backtrack.combinationSum(new int[] {1}, -1));
    }
}
