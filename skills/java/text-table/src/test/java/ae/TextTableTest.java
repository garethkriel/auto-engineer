package ae;

import static org.junit.jupiter.api.Assertions.*;

import ae.TextTable.Align;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTableTest {
    private static TextTable sample() {
        return TextTable.of(List.of("name", "qty"), List.of(Align.LEFT, Align.RIGHT))
                .withRow("apple", "10")
                .withRow("fig", "2")
                .withRow("kiwi", "n/a")
                .withRow("pear", "-1.5");
    }

    @Test
    void rendersAlignedColumns() {
        String expected = String.join("\n",
                "name  |  qty",
                "------+-----",
                "apple |   10",
                "fig   |    2",
                "kiwi  |  n/a",
                "pear  | -1.5");
        assertEquals(expected, sample().render());
    }

    @Test
    void headerOnlyTableRenders() {
        TextTable t = TextTable.of(List.of("a", "bb"), List.of(Align.LEFT, Align.LEFT));
        assertEquals("a | bb\n--+---", t.render());
    }

    @Test
    void lastLeftColumnHasNoTrailingSpaces() {
        TextTable t = TextTable.of(List.of("k", "v"), List.of(Align.RIGHT, Align.LEFT))
                .withRow("1", "longvalue").withRow("22", "x");
        assertEquals(" k | v\n---+----------\n 1 | longvalue\n22 | x", t.render());
    }

    @Test
    void nullCellBecomesEmpty() {
        TextTable t = TextTable.of(List.of("a", "b"), List.of(Align.RIGHT, Align.RIGHT))
                .withRow(null, "x");
        assertEquals("a | b\n--+--\n  | x", t.render());
    }

    @Test
    void withRowDoesNotMutateOriginal() {
        TextTable base = TextTable.of(List.of("a"), List.of(Align.LEFT));
        TextTable more = base.withRow("z");
        assertEquals(0, base.rowCount());
        assertEquals(1, more.rowCount());
    }

    @Test
    void numericAscendingPutsNonNumericLast() {
        String out = sample().sortedBy(1, true, false).render();
        assertEquals(List.of("pear", "fig", "apple", "kiwi"), firstColumn(out));
    }

    @Test
    void numericDescendingStillPutsNonNumericLast() {
        String out = sample().sortedBy(1, true, true).render();
        assertEquals(List.of("apple", "fig", "pear", "kiwi"), firstColumn(out));
    }

    @Test
    void textSortIsStable() {
        TextTable t = TextTable.of(List.of("g", "id"), List.of(Align.LEFT, Align.LEFT))
                .withRow("b", "1").withRow("a", "2").withRow("b", "3").withRow("a", "4");
        assertEquals(List.of("2", "4", "1", "3"), secondColumn(t.sortedBy(0, false, false).render()));
        assertEquals(List.of("1", "3", "2", "4"), secondColumn(t.sortedBy(0, false, true).render()));
    }

    @Test
    void nanAndInfinityStringsAreHandledAsNonNumericOrFinite() {
        TextTable t = TextTable.of(List.of("v"), List.of(Align.LEFT))
                .withRow("NaN").withRow("3").withRow("1");
        // "NaN" parses as double but must not break ordering: it sorts after real numbers.
        assertEquals(List.of("1", "3", "NaN"), firstColumn(t.sortedBy(0, true, false).render()));
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TextTable.of(List.of("a"), List.of(Align.LEFT, Align.LEFT)));
        assertThrows(IllegalArgumentException.class, () -> TextTable.of(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> sample().withRow("only one"));
        assertThrows(IllegalArgumentException.class, () -> sample().sortedBy(2, false, false));
        assertThrows(IllegalArgumentException.class, () -> sample().sortedBy(-1, false, false));
    }

    private static List<String> firstColumn(String rendered) {
        return rendered.lines().skip(2).map(l -> l.split("\\|")[0].trim()).toList();
    }

    private static List<String> secondColumn(String rendered) {
        return rendered.lines().skip(2).map(l -> l.split("\\|")[1].trim()).toList();
    }
}
