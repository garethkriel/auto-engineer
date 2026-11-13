package ae;

import java.util.OptionalInt;

/** Small array utilities. All methods are pure and reject null input. */
public final class ArrayOps {
    private ArrayOps() {}

    private static void check(int[] a) {
        if (a == null) {
            throw new IllegalArgumentException("array must not be null");
        }
    }

    /** Returns a copy of {@code a} rotated right by {@code k} (negative k rotates left). */
    public static int[] rotateRight(int[] a, int k) {
        check(a);
        int n = a.length;
        int[] out = new int[n];
        if (n == 0) {
            return out;
        }
        int shift = Math.floorMod(k, n);
        for (int i = 0; i < n; i++) {
            out[(i + shift) % n] = a[i];
        }
        return out;
    }

    /** Second largest distinct value, or empty if fewer than two distinct values. */
    public static OptionalInt secondLargestDistinct(int[] a) {
        check(a);
        boolean hasMax = false;
        boolean hasSecond = false;
        int max = 0;
        int second = 0;
        for (int v : a) {
            if (!hasMax || v > max) {
                if (hasMax) {
                    second = max;
                    hasSecond = true;
                }
                max = v;
                hasMax = true;
            } else if (v < max && (!hasSecond || v > second)) {
                second = v;
                hasSecond = true;
            }
        }
        return hasSecond ? OptionalInt.of(second) : OptionalInt.empty();
    }

    /** Length of the longest run of equal consecutive values; 0 for an empty array. */
    public static int longestRun(int[] a) {
        check(a);
        int best = 0;
        int cur = 0;
        for (int i = 0; i < a.length; i++) {
            cur = (i > 0 && a[i] == a[i - 1]) ? cur + 1 : 1;
            best = Math.max(best, cur);
        }
        return best;
    }
}
