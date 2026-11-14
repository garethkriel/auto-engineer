package ae;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeverityTest {
    @ParameterizedTest
    @CsvSource({"low,LOW", "  Medium ,MEDIUM", "HIGH,HIGH", "critical,CRITICAL"})
    void parsesCaseInsensitively(String in, Severity expected) {
        assertEquals(new Result.Ok<Severity, String>(expected), Severity.parse(in));
    }

    @Test
    void parseFailureDescribesInput() {
        assertEquals(new Result.Err<Severity, String>("unknown severity: 'nope'"), Severity.parse("nope"));
        assertFalse(Severity.parse("").isOk());
        assertThrows(NullPointerException.class, () -> Severity.parse(null));
    }

    @Test
    void weightsAreIncreasing() {
        assertEquals(1, Severity.LOW.weight());
        assertEquals(2, Severity.MEDIUM.weight());
        assertEquals(4, Severity.HIGH.weight());
        assertEquals(8, Severity.CRITICAL.weight());
    }

    @Test
    void atLeastUsesOrder() {
        assertTrue(Severity.HIGH.atLeast(Severity.MEDIUM));
        assertTrue(Severity.HIGH.atLeast(Severity.HIGH));
        assertFalse(Severity.LOW.atLeast(Severity.CRITICAL));
    }

    @Test
    void parseThenSequence() {
        var r = Result.sequence(java.util.List.of(Severity.parse("low"), Severity.parse("high")));
        assertEquals(new Result.Ok<java.util.List<Severity>, String>(java.util.List.of(Severity.LOW, Severity.HIGH)), r);
    }
}
