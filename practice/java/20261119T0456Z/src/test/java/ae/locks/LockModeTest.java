package ae.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LockModeTest {
    private static final LockMode[] M = LockMode.values();

    /** Rows/columns in declaration order IS, IX, S, SIX, X. */
    private static final String[] MATRIX = {
        "yyyyn", // IS
        "yynnn", // IX
        "ynynn", // S
        "ynnnn", // SIX
        "nnnnn", // X
    };

    /** join table, same order; entries are mode names. */
    private static final String[][] JOIN = {
        {"IS", "IX", "S", "SIX", "X"},
        {"IX", "IX", "SIX", "SIX", "X"},
        {"S", "SIX", "S", "SIX", "X"},
        {"SIX", "SIX", "SIX", "SIX", "X"},
        {"X", "X", "X", "X", "X"},
    };

    @Test
    void declarationOrderIsAsDocumented() {
        assertEquals("[IS, IX, S, SIX, X]", java.util.Arrays.toString(M));
    }

    @Test
    void compatibilityMatrixMatchesLiteralTable() {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                boolean expected = MATRIX[i].charAt(j) == 'y';
                assertEquals(expected, M[i].compatibleWith(M[j]), M[i] + " vs " + M[j]);
            }
        }
    }

    @Test
    void joinMatchesLiteralTable() {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                assertEquals(JOIN[i][j], M[i].join(M[j]).name(), M[i] + " join " + M[j]);
            }
        }
    }

    @Test
    void joinIsCommutativeAssociativeIdempotent() {
        for (LockMode a : M) {
            assertEquals(a, a.join(a));
            for (LockMode b : M) {
                assertEquals(a.join(b), b.join(a));
                for (LockMode c : M) {
                    assertEquals(a.join(b).join(c), a.join(b.join(c)), a + "," + b + "," + c);
                }
            }
        }
    }

    @Test
    void joinIsAnUpperBoundThatNeverGainsCompatibility() {
        // anything compatible with the join is compatible with both parts
        for (LockMode a : M) {
            for (LockMode b : M) {
                LockMode j = a.join(b);
                assertTrue(j.covers(a));
                assertTrue(j.covers(b));
                for (LockMode other : M) {
                    if (j.compatibleWith(other)) {
                        assertTrue(a.compatibleWith(other) && b.compatibleWith(other));
                    }
                }
            }
        }
    }

    @Test
    void compatibilityIsSymmetric() {
        for (LockMode a : M) {
            for (LockMode b : M) {
                assertEquals(a.compatibleWith(b), b.compatibleWith(a));
            }
        }
    }

    @Test
    void coversAndIntention() {
        assertTrue(LockMode.X.covers(LockMode.S));
        assertTrue(LockMode.SIX.covers(LockMode.IX));
        assertFalse(LockMode.S.covers(LockMode.IX));
        assertFalse(LockMode.IX.covers(LockMode.S));
        assertEquals(LockMode.IS, LockMode.IS.intention());
        assertEquals(LockMode.IS, LockMode.S.intention());
        assertEquals(LockMode.IX, LockMode.IX.intention());
        assertEquals(LockMode.IX, LockMode.SIX.intention());
        assertEquals(LockMode.IX, LockMode.X.intention());
    }
}
