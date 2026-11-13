package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ArrayOpsTest {
    @Test void rotateRightBasic() {
        assertArrayEquals(new int[] {4, 5, 1, 2, 3}, ArrayOps.rotateRight(new int[] {1, 2, 3, 4, 5}, 2));
    }
    @Test void rotateRightNegativeAndLarge() {
        assertArrayEquals(new int[] {3, 4, 5, 1, 2}, ArrayOps.rotateRight(new int[] {1, 2, 3, 4, 5}, -2));
        assertArrayEquals(new int[] {5, 1, 2, 3, 4}, ArrayOps.rotateRight(new int[] {1, 2, 3, 4, 5}, 1001));
        assertArrayEquals(new int[] {1, 2}, ArrayOps.rotateRight(new int[] {1, 2}, Integer.MIN_VALUE));
    }
    @Test void rotateDoesNotMutateInput() {
        int[] in = {1, 2, 3};
        ArrayOps.rotateRight(in, 1);
        assertArrayEquals(new int[] {1, 2, 3}, in);
    }
    @Test void rotateEmpty() {
        assertEquals(0, ArrayOps.rotateRight(new int[0], 5).length);
    }
    @Test void secondLargestIgnoresDuplicates() {
        assertEquals(OptionalInt.of(4), ArrayOps.secondLargestDistinct(new int[] {5, 5, 4, 4, 1}));
        assertEquals(OptionalInt.of(-2), ArrayOps.secondLargestDistinct(new int[] {-1, -2, -3}));
    }
    @Test void secondLargestAbsent() {
        assertTrue(ArrayOps.secondLargestDistinct(new int[] {7, 7, 7}).isEmpty());
        assertTrue(ArrayOps.secondLargestDistinct(new int[0]).isEmpty());
    }
    @Test void secondLargestHandlesMinValue() {
        assertEquals(OptionalInt.of(Integer.MIN_VALUE),
            ArrayOps.secondLargestDistinct(new int[] {Integer.MIN_VALUE, 0}));
    }
    @Test void longestRun() {
        assertEquals(3, ArrayOps.longestRun(new int[] {1, 1, 2, 2, 2, 1}));
        assertEquals(1, ArrayOps.longestRun(new int[] {1, 2, 3}));
        assertEquals(0, ArrayOps.longestRun(new int[0]));
    }
    @Test void nullsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ArrayOps.rotateRight(null, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayOps.secondLargestDistinct(null));
        assertThrows(IllegalArgumentException.class, () -> ArrayOps.longestRun(null));
    }
}
