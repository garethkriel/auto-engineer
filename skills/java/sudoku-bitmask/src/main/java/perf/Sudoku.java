package perf;

import java.util.Optional;

/** Bitmask Sudoku solver: row/column/box candidate sets are 9-bit ints. */
public final class Sudoku {
    private static final int ALL = 0x1FF;

    private Sudoku() {}

    /** Parses an 81-character puzzle into digits (0 = empty), validating givens. */
    public static int[] parse(String puzzle) {
        if (puzzle == null || puzzle.length() != 81) {
            throw new IllegalArgumentException("puzzle must have 81 characters");
        }
        int[] grid = new int[81];
        int[] used = new int[27];
        for (int i = 0; i < 81; i++) {
            char ch = puzzle.charAt(i);
            if (ch == '.' || ch == '0') {
                continue;
            }
            if (ch < '1' || ch > '9') {
                throw new IllegalArgumentException("bad character '" + ch + "' at " + i);
            }
            int bit = 1 << (ch - '1');
            int r = i / 9;
            int c = i % 9;
            int b = r / 3 * 3 + c / 3;
            if (((used[r] | used[9 + c] | used[18 + b]) & bit) != 0) {
                throw new IllegalArgumentException("conflicting given at " + i);
            }
            used[r] |= bit;
            used[9 + c] |= bit;
            used[18 + b] |= bit;
            grid[i] = ch - '0';
        }
        return grid;
    }

    /** Returns the first solution found, or empty if the puzzle has none. */
    public static Optional<String> solve(String puzzle) {
        State s = new State(parse(puzzle));
        if (s.search(1) == 0) {
            return Optional.empty();
        }
        return Optional.of(s.firstSolution);
    }

    /** Counts solutions, stopping once {@code limit} have been found. */
    public static int countSolutions(String puzzle, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return new State(parse(puzzle)).search(limit);
    }

    /** True iff the string is a complete grid where every unit holds 1-9 exactly once. */
    public static boolean isValidSolution(String grid) {
        if (grid == null || grid.length() != 81) {
            return false;
        }
        int[] used = new int[27];
        for (int i = 0; i < 81; i++) {
            char ch = grid.charAt(i);
            if (ch < '1' || ch > '9') {
                return false;
            }
            int bit = 1 << (ch - '1');
            int r = i / 9;
            int c = i % 9;
            used[r] |= bit;
            used[9 + c] |= bit;
            used[18 + r / 3 * 3 + c / 3] |= bit;
        }
        for (int m : used) {
            if (m != ALL) {
                return false;
            }
        }
        return true;
    }

    /** Mutable search state, private to one call so the public API stays pure. */
    private static final class State {
        final int[] cells;
        final int[] rows = new int[9];
        final int[] cols = new int[9];
        final int[] boxes = new int[9];
        String firstSolution;

        State(int[] grid) {
            cells = grid;
            for (int i = 0; i < 81; i++) {
                if (grid[i] != 0) {
                    place(i, 1 << (grid[i] - 1));
                }
            }
        }

        private void place(int i, int bit) {
            rows[i / 9] ^= bit;
            cols[i % 9] ^= bit;
            boxes[i / 27 * 3 + i % 9 / 3] ^= bit;
        }

        private int candidates(int i) {
            return ALL & ~(rows[i / 9] | cols[i % 9] | boxes[i / 27 * 3 + i % 9 / 3]);
        }

        /** Counts solutions up to limit; records the first one. */
        int search(int limit) {
            int best = -1;
            int bestMask = 0;
            int bestCount = 10;
            for (int i = 0; i < 81; i++) {
                if (cells[i] != 0) {
                    continue;
                }
                int mask = candidates(i);
                int n = Integer.bitCount(mask);
                if (n < bestCount) {
                    best = i;
                    bestMask = mask;
                    bestCount = n;
                    if (n <= 1) {
                        break;
                    }
                }
            }
            if (best < 0) {
                if (firstSolution == null) {
                    StringBuilder sb = new StringBuilder(81);
                    for (int v : cells) {
                        sb.append((char) ('0' + v));
                    }
                    firstSolution = sb.toString();
                }
                return 1;
            }
            int found = 0;
            for (int m = bestMask; m != 0 && found < limit; m &= m - 1) {
                int bit = m & -m;
                cells[best] = Integer.numberOfTrailingZeros(bit) + 1;
                place(best, bit);
                found += search(limit - found);
                place(best, bit);
                cells[best] = 0;
            }
            return found;
        }
    }
}
