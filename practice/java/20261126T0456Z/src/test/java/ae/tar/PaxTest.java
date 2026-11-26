package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaxTest {
    @Test
    void recordLiteral() {
        // " path=abc\n" is 10 bytes; one digit would make 11, two digits "12" -> 12 bytes total? 2 + 10 = 12.
        assertEquals("12 path=abc\n", Pax.record("path", "abc"));
    }

    @Test
    void lengthCountsItsOwnDigitsAcrossTheHundredBoundary() {
        // record = digits + " k=" + value + "\n" = digits + 4 + v   (v = value length)
        assertEquals("98 k=" + "x".repeat(92) + "\n", Pax.record("k", "x".repeat(92))); // 2 + 4 + 92 = 98
        assertEquals("99 k=" + "x".repeat(93) + "\n", Pax.record("k", "x".repeat(93))); // 2 + 4 + 93 = 99
        // v = 94: two digits would give 100 (three digits), so three digits: 3 + 4 + 94 = 101
        assertEquals("101 k=" + "x".repeat(94) + "\n", Pax.record("k", "x".repeat(94)));
        String r = Pax.record("k", "x".repeat(94));
        assertEquals(101, r.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void lengthCountsUtf8BytesNotChars() {
        String r = Pax.record("path", "é"); // value is 2 bytes: 2 + " path=" (6) + 2 + "\n"(1) -> digits 2: total 2+1+4+1+2+1 = 11
        assertEquals("11 path=é\n", r);
        assertEquals(11, r.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void parseRoundTripsRecordsInOrder() throws Exception {
        String text = Pax.record("path", "a/b") + Pax.record("uid", "12345678") + Pax.record("linkpath", "t");
        Map<String, String> m = Pax.parse(text.getBytes(StandardCharsets.UTF_8));
        assertEquals("[path, uid, linkpath]", m.keySet().toString());
        assertEquals("a/b", m.get("path"));
        assertEquals("12345678", m.get("uid"));
    }

    @Test
    void valueMayContainEqualsAndNewlines() throws Exception {
        String v = "a=b\nc";
        Map<String, String> m = Pax.parse(Pax.record("comment", v).getBytes(StandardCharsets.UTF_8));
        assertEquals(v, m.get("comment"));
    }

    @Test
    void laterDuplicateWins() throws Exception {
        String text = Pax.record("path", "first") + Pax.record("path", "second");
        assertEquals("second", Pax.parse(text.getBytes(StandardCharsets.UTF_8)).get("path"));
    }

    @Test
    void emptyDataIsEmptyMap() throws Exception {
        assertTrue(Pax.parse(new byte[0]).isEmpty());
    }

    private static void rejects(String raw, String messagePart) {
        TarFormatException e = assertThrows(TarFormatException.class, () -> Pax.parse(raw.getBytes(StandardCharsets.UTF_8)), raw);
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void controlIsAccepted() throws Exception {
        assertEquals("b", Pax.parse("6 a=b\n".getBytes(StandardCharsets.UTF_8)).get("a")); // 6 bytes exactly
    }

    @Test
    void malformedRecordsAreRejectedForTheirOwnReason() {
        rejects("5 a=b\n", "newline");        // declared 5: the record "5 a=b" does not end with a newline
        rejects("7 a=b\n", "length");         // declared 7, only 6 bytes exist
        rejects("06 a=b\n", "length");        // leading zero (and then 7 bytes): digits must not start with 0
        rejects("6 a=bc", "newline");         // 6 bytes, last byte not '\n'
        rejects("6 abc\n", "'='");            // no '='
        rejects("5 =b\n", "key");             // empty key
        rejects("6 a=b\nX", "length");        // trailing garbage after a good record
        rejects("a 1=b\n", "length");         // non-digit length
        rejects(" 6 a=b\n", "length");        // leading space
        rejects("12345678 a=b\n", "too many digits");  // eight digits: overflow guard
        rejects("4 =\n", "out of range");   // shortest legal record is "5 k=\n": a 4-byte record is too short
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] bad = {'7', ' ', 'a', '=', (byte) 0xC3, (byte) 0x28, '\n'}; // C3 28 is malformed
        assertEquals(7, bad.length);
        assertThrows(TarFormatException.class, () -> Pax.parse(bad));
    }
}
