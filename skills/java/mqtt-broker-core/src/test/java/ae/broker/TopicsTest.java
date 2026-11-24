package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TopicsTest {
    @ParameterizedTest
    @CsvSource({
        "a/b, a/b, true",
        "a/b, a/c, false",
        "a/+, a/b, true",
        "a/+, a/b/c, false",
        "a/+, a, false",
        "+/b, a/b, true",
        "+, a, true",
        "+, a/b, false",
        "#, a, true",
        "#, a/b/c, true",
        "a/#, a, true",
        "a/#, a/b/c, true",
        "a/#, b, false",
        "a/+/c, a/x/c, true",
        "a/+/c, a/x/d, false",
        "+/+, a/b, true",
        "+/+, a/, true",
        "a/+, a/, true",
        "a/b, a/b/, false",
    })
    void table(String filter, String topic, boolean expected) {
        assertEquals(expected, Topics.matches(filter, topic), filter + " vs " + topic);
    }

    @Test
    void emptyLevelsAreRealLevels() {
        assertTrue(Topics.matches("/a", "/a"));
        assertTrue(Topics.matches("+/a", "/a"));
        assertFalse(Topics.matches("a", "/a"));
        assertTrue(Topics.matches("#", "/"));
    }

    @Test
    void dollarTopicsHiddenFromLeadingWildcards() {
        assertFalse(Topics.matches("#", "$SYS/x"));
        assertFalse(Topics.matches("+/x", "$SYS/x"));
        assertTrue(Topics.matches("$SYS/#", "$SYS/x"));
        assertTrue(Topics.matches("$SYS/+", "$SYS/x"));
        assertTrue(Topics.matches("a/$b", "a/$b"));
        assertTrue(Topics.matches("a/+", "a/$b"));
    }

    @Test
    void validTopics() {
        Topics.validateTopic("a");
        Topics.validateTopic("a//b");
        Topics.validateTopic("/");
        Topics.validateTopic("$SYS/x");
    }

    @ParameterizedTest
    @CsvSource({"''", "a/+", "a/#", "a+b", "#", "a\u0000b"})
    void invalidTopics(String t) {
        assertThrows(IllegalArgumentException.class, () -> Topics.validateTopic(t));
    }

    @Test
    void validFilters() {
        for (String f : new String[] {"#", "+", "a/#", "+/+/#", "a/+/b", "/", "a//b", "$SYS/#"}) {
            Topics.validateFilter(f);
        }
    }

    @Test
    void invalidFilters() {
        for (String f : new String[] {"", "a#", "#/a", "a/#/b", "a+", "+a", "a/b+/c", "a/##", "a/#b", "a\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> Topics.validateFilter(f), f);
        }
        assertThrows(IllegalArgumentException.class, () -> Topics.validateFilter(null));
        assertThrows(IllegalArgumentException.class, () -> Topics.validateTopic(null));
    }
}
