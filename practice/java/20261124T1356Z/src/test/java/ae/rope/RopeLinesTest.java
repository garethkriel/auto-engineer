package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RopeLinesTest {

    // offsets: a0 b1 \n2 c3 d4 e5 \n6 \n7 f8   (length 9)
    static final String TEXT = "ab\ncde\n\nf";

    @Test
    void literalLineTable() {
        Rope r = Rope.of(TEXT);
        assertEquals(4, r.lineCount());
        assertEquals(0, r.lineStart(0));
        assertEquals(3, r.lineStart(1));
        assertEquals(7, r.lineStart(2));
        assertEquals(8, r.lineStart(3));
        assertEquals("ab", r.line(0));
        assertEquals("cde", r.line(1));
        assertEquals("", r.line(2));
        assertEquals("f", r.line(3));
    }

    @Test
    void lineOfAtEveryOffset() {
        Rope r = Rope.of(TEXT);
        int[] expected = {0, 0, 0, 1, 1, 1, 1, 2, 3, 3};
        for (int off = 0; off <= 9; off++) {
            assertEquals(expected[off], r.lineOf(off), "offset " + off);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> r.lineOf(10));
        assertThrows(IndexOutOfBoundsException.class, () -> r.lineOf(-1));
    }

    @Test
    void positionsAndOffsets() {
        Rope r = Rope.of(TEXT);
        assertEquals(new Rope.Position(1, 2), r.positionOf(5));
        assertEquals(new Rope.Position(0, 2), r.positionOf(2));
        assertEquals(new Rope.Position(3, 1), r.positionOf(9));
        assertEquals(6, r.offsetOf(1, 3), "column == line length is the spot before the newline");
        assertEquals(9, r.offsetOf(3, 1));
        assertEquals(7, r.offsetOf(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> r.offsetOf(1, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> r.offsetOf(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> r.offsetOf(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.lineStart(4));
        assertThrows(IndexOutOfBoundsException.class, () -> r.line(-1));
    }

    @Test
    void emptyAndTrailingNewline() {
        Rope e = Rope.empty();
        assertEquals(1, e.lineCount());
        assertEquals("", e.line(0));
        assertEquals(0, e.lineStart(0));
        assertEquals(new Rope.Position(0, 0), e.positionOf(0));

        Rope t = Rope.of("x\n");
        assertEquals(2, t.lineCount());
        assertEquals("x", t.line(0));
        assertEquals("", t.line(1));
        assertEquals(2, t.lineStart(1));
        assertEquals(1, t.lineOf(2));
    }

    @Test
    void carriageReturnIsOrdinaryCharacter() {
        Rope r = Rope.of("a\r\nb");
        assertEquals(2, r.lineCount());
        assertEquals("a\r", r.line(0));
        assertEquals("b", r.line(1));
    }

    @Test
    void manyLinesAcrossManyLeavesMatchNaiveOracle() {
        StringBuilder sb = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            starts.add(sb.length());
            sb.append("line ").append(i).append(i % 7 == 0 ? "" : "!".repeat(i % 5)).append('\n');
        }
        starts.add(sb.length()); // final empty line after the trailing newline
        Rope r = Rope.of(sb);
        assertEquals(201, r.lineCount());
        for (int k = 0; k < starts.size(); k++) {
            assertEquals(starts.get(k), r.lineStart(k), "start of line " + k);
            int end = k + 1 < starts.size() ? starts.get(k + 1) - 1 : sb.length();
            assertEquals(sb.substring(starts.get(k), end), r.line(k), "text of line " + k);
            assertEquals(k, r.lineOf(starts.get(k)), "lineOf start " + k);
        }
    }

    @Test
    void editsKeepLineIndexCurrent() {
        Rope r = Rope.of("one\ntwo\nthree");
        assertEquals(3, r.lineCount());
        Rope inserted = r.insert(3, "\nextra");
        assertEquals(4, inserted.lineCount());
        assertEquals("extra", inserted.line(1));
        Rope deleted = inserted.delete(3, 9); // removes "\nextra"
        assertEquals(r, deleted);
        assertEquals(3, deleted.lineCount());
        Rope joined = r.delete(3, 4); // remove the first newline
        assertEquals(2, joined.lineCount());
        assertEquals("onetwo", joined.line(0));
    }
}
