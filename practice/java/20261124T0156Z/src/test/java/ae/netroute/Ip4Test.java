package ae.netroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class Ip4Test {
    @ParameterizedTest
    @CsvSource({"0.0.0.0,0", "0.0.0.1,1", "1.2.3.4,16909060", "10.0.0.0,167772160",
        "192.168.1.255,3232236031", "255.255.255.255,4294967295"})
    void parsesAndFormats(String text, long value) {
        assertEquals(value, Ip4.parse(text));
        assertEquals(text, Ip4.format(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.2.3", "1.2.3.4.5", "256.0.0.1", "1.2.3.-4", "01.2.3.4", "1.2.3.04",
        "1.2.3.", ".1.2.3", "a.b.c.d", "1..2.3", " 1.2.3.4", "1.2.3.4 ", "1.2.3.1000", "+1.2.3.4"})
    void rejectsMalformed(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Ip4.parse(text));
        assertTrue(e.getMessage().contains("'" + text + "'"), e.getMessage());
    }

    @Test
    void formatRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Ip4.format(-1));
        assertThrows(IllegalArgumentException.class, () -> Ip4.format(1L << 32));
    }

    @Test
    void roundTripRandom() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            long v = r.nextLong() & 0xFFFFFFFFL;
            assertEquals(v, Ip4.parse(Ip4.format(v)));
        }
    }
}
