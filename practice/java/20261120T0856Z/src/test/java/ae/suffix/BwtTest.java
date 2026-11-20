package ae.suffix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BwtTest {
    @Test
    void bananaTransform() {
        assertEquals("annb\0aa", Bwt.transform("banana"));
    }

    @Test
    void inverseOfBanana() {
        assertEquals("banana", Bwt.inverse("annb\0aa"));
    }

    @Test
    void emptyString() {
        assertEquals("\0", Bwt.transform(""));
        assertEquals("", Bwt.inverse("\0"));
    }

    @Test
    void sentinelInInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> Bwt.transform("a\0b"));
    }

    @Test
    void invalidBwtRejected() {
        assertThrows(IllegalArgumentException.class, () -> Bwt.inverse("ab")); // no sentinel
        assertThrows(IllegalArgumentException.class, () -> Bwt.inverse("\0\0")); // two sentinels
        assertThrows(IllegalArgumentException.class, () -> Bwt.inverse(""));
        // same multiset as the valid "b\0a" / "ab\0" but not the BWT of anything
        for (String bad : new String[] {"\0ab", "\0ba", "a\0b", "ba\0"}) {
            assertThrows(IllegalArgumentException.class, () -> Bwt.inverse(bad), bad);
        }
        assertEquals("ab", Bwt.inverse("b\0a")); // control: valid ones load
        assertEquals("ba", Bwt.inverse("ab\0"));
    }

    @Test
    void randomRoundTrip() {
        Random r = new Random(99);
        for (int i = 0; i < 400; i++) {
            String s = Naive.random(r, r.nextInt(80), 1 + r.nextInt(5));
            String b = Bwt.transform(s);
            assertEquals(s.length() + 1, b.length());
            assertEquals(s, Bwt.inverse(b), s);
        }
    }

    @Test
    void bwtGroupsRepeatsIntoRuns() {
        String s = "abracadabra".repeat(6);
        String b = Bwt.transform(s);
        int runsIn = runs(s), runsOut = runs(b);
        assertTrue(runsOut < runsIn, runsOut + " >= " + runsIn);
    }

    private static int runs(String s) {
        int n = s.isEmpty() ? 0 : 1;
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != s.charAt(i - 1)) n++;
        return n;
    }
}
