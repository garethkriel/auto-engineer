package ae.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespTest {
    private static Resp.Value parse(String s) throws IOException {
        return Resp.parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String enc(Resp.Value v) {
        return new String(Resp.encode(v), StandardCharsets.UTF_8);
    }

    @Test
    void roundTripsEveryType() throws IOException {
        List<Resp.Value> vals = List.of(
                new Resp.Simple("OK"), new Resp.Err("ERR boom"), new Resp.Int(-42),
                new Resp.Bulk("héllo"), new Resp.Bulk(null), new Resp.Bulk(""),
                new Resp.Arr(null), new Resp.Arr(List.of()),
                new Resp.Arr(List.of(new Resp.Int(1), new Resp.Arr(List.of(new Resp.Bulk("x"))))));
        for (Resp.Value v : vals) {
            assertEquals(v, Resp.parse(new ByteArrayInputStream(Resp.encode(v))), enc(v));
        }
    }

    @Test
    void bulkLengthIsInBytes() {
        assertEquals("$6\r\nhéllo\r\n", enc(new Resp.Bulk("héllo")));
    }

    @Test
    void commandBuilder() {
        assertEquals("*2\r\n$3\r\nGET\r\n$1\r\nk\r\n", enc(Resp.command("GET", "k")));
    }

    @Test
    void cleanEofReturnsNull() throws IOException {
        assertNull(parse(""));
    }

    @Test
    void parsesTwoValuesFromOneStream() throws IOException {
        var in = new ByteArrayInputStream("+A\r\n:5\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(new Resp.Simple("A"), Resp.parse(in));
        assertEquals(new Resp.Int(5), Resp.parse(in));
        assertNull(Resp.parse(in));
    }

    @Test
    void malformedInputsThrow() {
        String[] bad = {"?x\r\n", "$-2\r\n", "$3\r\nabcd\r\n", "$3\r\nab", "*2\r\n:1\r\n", ":abc\r\n",
                "+OK", "$3\r\nabc\r", "*-5\r\n", "*2000\r\n", "$2000000\r\n", ":\r\n", "+OK\n"};
        for (String b : bad) {
            assertThrows(RespException.class, () -> parse(b), b);
        }
    }

    @Test
    void nestingLimit() {
        String deep = "*1\r\n".repeat(40) + ":1\r\n";
        assertThrows(RespException.class, () -> parse(deep));
        String ok = "*1\r\n".repeat(30) + ":1\r\n";
        assertDoesNotThrow(() -> parse(ok));
    }

    @Test
    void controlCharactersInBulkSurvive() throws IOException {
        Resp.Value v = new Resp.Bulk("a\r\nb");
        assertEquals(v, Resp.parse(new ByteArrayInputStream(Resp.encode(v))));
    }

    @Test
    void simpleStringRejectsNewlineOnEncode() {
        assertThrows(IllegalArgumentException.class, () -> Resp.encode(new Resp.Simple("a\r\nb")));
    }
}
