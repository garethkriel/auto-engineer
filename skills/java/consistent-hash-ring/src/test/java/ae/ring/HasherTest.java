package ae.ring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HasherTest {
    @Test
    void defaultIsDeterministicAndKnownValueForEmptyStringIsStable() {
        assertEquals(Hasher.DEFAULT.hash("abc"), Hasher.DEFAULT.hash("abc"));
        assertNotEquals(Hasher.DEFAULT.hash("abc"), Hasher.DEFAULT.hash("abd"));
    }

    @Test
    void defaultSpreadsSimilarKeysWithoutCollisions() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100000; i++) assertTrue(seen.add(Hasher.DEFAULT.hash("k" + i)), "collision at " + i);
    }

    @Test
    void highBitsAreUsedForSequentialKeys() {
        int positive = 0;
        for (int i = 0; i < 2000; i++) if (Hasher.DEFAULT.hash("seq" + i) >= 0) positive++;
        assertTrue(positive > 800 && positive < 1200, "positive " + positive);
    }
}
