package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryParamsTest {
    @Test
    void parsesPairsInOrderWithDuplicates() {
        QueryParams q = QueryParams.parse("a=1&b=2&a=3&c&d=&=e");
        assertEquals(List.of("1", "3"), q.getAll("a"));
        assertEquals("1", q.get("a").orElseThrow());
        assertEquals("", q.get("c").orElseThrow());
        assertEquals("", q.get("d").orElseThrow());
        assertEquals("e", q.get("").orElseThrow());
        assertTrue(q.get("zzz").isEmpty());
        assertEquals(6, q.size());
    }

    @Test
    void decodesPlusAndPercent() {
        QueryParams q = QueryParams.parse("k%20x=a+b%2Bc&%C3%A9=%F0%9F%98%80");
        assertEquals("a b+c", q.get("k x").orElseThrow());
        assertEquals("\uD83D\uDE00", q.get("\u00e9").orElseThrow());
    }

    @Test
    void emptyAndNullAreDifferent() {
        assertEquals(0, QueryParams.parse("").size());
        assertEquals(0, QueryParams.parse("&&").size());
        assertEquals(1, QueryParams.parse("&a&").size());
    }

    @Test
    void reencodesCanonically() {
        QueryParams q = QueryParams.parse("a=b c&d=%7e&e=1+2");
        assertEquals("a=b+c&d=~&e=1+2", q.toString());
        assertEquals("a=1&a=2&b=%26%3D%2B%25", QueryParams.of("a", "1").with("a", "2").with("b", "&=+%").toString());
    }

    @Test
    void withReplacesAllWithoutRemoves() {
        QueryParams q = QueryParams.parse("a=1&b=2&a=3");
        assertEquals("a=9&b=2", q.set("a", "9").toString());
        assertEquals("b=2", q.without("a").toString());
        assertEquals("a=1&b=2&a=3", q.toString()); // immutable
        assertEquals("a=1&b=2&a=3&c=4", q.with("c", "4").toString());
        assertEquals("a=1&b=2&a=3&z=9", q.set("z", "9").toString());
    }

    @Test
    void roundTripOfArbitraryValues() {
        java.util.Random r = new java.util.Random(3);
        String alphabet = "ab =&+%#?/\u00e9\uD83D\uDE00;";
        for (int i = 0; i < 300; i++) {
            QueryParams q = QueryParams.of();
            int n = r.nextInt(4);
            for (int j = 0; j < n; j++) q = q.with(rnd(r, alphabet), rnd(r, alphabet));
            QueryParams back = QueryParams.parse(q.toString());
            assertEquals(q, back, q.toString());
        }
    }

    private static String rnd(java.util.Random r, String alphabet) {
        StringBuilder sb = new StringBuilder();
        int[] cps = alphabet.codePoints().toArray();
        for (int i = r.nextInt(5); i > 0; i--) sb.appendCodePoint(cps[r.nextInt(cps.length)]);
        // a lone empty pair "=" is fine; but empty name+empty value serialises as "=" which parses back
        return sb.toString();
    }

    @Test
    void integratesWithUri() {
        Uri u = Uri.parse("http://h/p?x=1&y=a+b#f");
        assertEquals("a b", u.queryParams().get("y").orElseThrow());
        Uri v = u.withQuery(u.queryParams().set("x", "2"));
        assertEquals("http://h/p?x=2&y=a+b#f", v.toString());
        assertEquals(0, Uri.parse("http://h/p").queryParams().size());
        assertNull(u.withQuery(QueryParams.of()).query());
    }
}
