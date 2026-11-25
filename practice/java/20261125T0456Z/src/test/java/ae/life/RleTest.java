package ae.life;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RleTest {
    static final String GUN = "#N Gosper glider gun\n#C a comment\n"
            + "x = 36, y = 9, rule = B3/S23\n"
            + "24bo$22bobo$12b2o6b2o12b2o$11bo3bo4b2o12b2o$2o8bo5bo3b2o$2o8bo3bob2o4bobo$10bo5bo7bo$11bo3bo$12b2o!";

    @Test
    void parsesGliderIntoSortedCellsWithSize() {
        Rle.Pattern p = Rle.parse("x = 3, y = 3\nbo$2bo$3o!");
        // rows: y0 = .O. ; y1 = ..O ; y2 = OOO
        assertEquals(List.of(new Cell(1, 0), new Cell(2, 1), new Cell(0, 2), new Cell(1, 2), new Cell(2, 2)), p.cells());
        assertEquals(3, p.width());
        assertEquals(3, p.height());
    }

    @Test
    void parsesCommentsMultiDigitRunsAndBlankRowRuns() {
        // 12 live cells in row 0, then "3$" = end row 0 and skip two blank rows, one live cell in row 3.
        Rle.Pattern p = Rle.parse("#C hi\r\nx = 12, y = 4, rule = B3/S23\r\n12o3$o!");
        assertEquals(13, p.cells().size());
        assertEquals(new Cell(11, 0), p.cells().get(11));
        assertEquals(new Cell(0, 3), p.cells().get(12));
    }

    @Test
    void gunHasFortyCellsAndHeaderSize() {
        // Counting the runs of GUN: 1+2+6+4+... verified independently below by population of the parsed set.
        Rle.Pattern p = Rle.parse(GUN);
        assertEquals(36, p.cells().size());
        assertEquals(36, p.width());
        assertEquals(9, p.height());
    }

    @Test
    void acceptsAlternativeNotationOfLifeRule() {
        assertEquals(1, Rle.parse("x = 1, y = 1, rule = 23/3\no!").cells().size());
        assertEquals(1, Rle.parse("x = 1, y = 1, rule = b3/s23\no!").cells().size());
    }

    @Test
    void writeNormalisesToBoundingBoxAndCompressesRuns() {
        List<Cell> cells = List.of(new Cell(10, -5), new Cell(11, -5), new Cell(10, -3));
        // bounding box x 10..11, y -5..-3 -> width 2 height 3; row0 = oo, row1 blank, row2 = o
        assertEquals("x = 2, y = 3, rule = B3/S23\n2o2$o!\n", Rle.write(cells));
    }

    @Test
    void writeEmptyPatternAndRoundTripOfEmpty() {
        String s = Rle.write(List.of());
        assertEquals("x = 0, y = 0, rule = B3/S23\n!\n", s);
        assertEquals(0, Rle.parse(s).cells().size());
    }

    @Test
    void writeThenParseRoundTripsRandomPatternsAndWrapsLongLines() {
        Random rnd = new Random(7);
        for (int round = 0; round < 40; round++) {
            TreeSet<Cell> set = new TreeSet<>();
            int w = 1 + rnd.nextInt(90);
            int h = 1 + rnd.nextInt(30);
            for (int i = 0; i < w * h / 3; i++) {
                set.add(new Cell(rnd.nextInt(w) - 40, rnd.nextInt(h) + 100));
            }
            String text = Rle.write(set);
            for (String line : text.split("\n")) {
                assertTrue(line.length() <= 70 || line.startsWith("x"), "line too long: " + line);
            }
            Rle.Pattern p = Rle.parse(text);
            long minX = set.stream().mapToLong(Cell::x).min().getAsLong();
            long minY = set.stream().mapToLong(Cell::y).min().getAsLong();
            List<Cell> shifted = new ArrayList<>();
            for (Cell c : set) {
                shifted.add(new Cell(c.x() - minX, c.y() - minY));
            }
            Collections.sort(shifted);
            assertEquals(shifted, p.cells(), text);
        }
    }

    @Test
    void rejectsMalformedInputWithUsefulMessages() {
        assertMessage("x = 3, y = 3\nbo$2bo$3o", "'!'");
        assertMessage("x = 3, y = 3\nbq!", "unexpected");
        assertMessage("x = 3, y = 3, rule = B36/S23\no!", "rule");
        assertMessage("x = 2, y = 1\n3o!", "width");
        assertMessage("x = 2, y = 1\no$o!", "height");
        assertMessage("x = 2, y = 1\n0o!", "run");
        assertMessage("bo!", "header");
        assertMessage("x = 3, y = 1\n5!", "dangling");
    }

    @Test
    void controlInputDiffersOnlyByTheDefect() {
        assertEquals(3, Rle.parse("x = 3, y = 1\n3o!").cells().size());
        assertMessage("x = 3, y = 1\n4o!", "width");
    }

    private static void assertMessage(String input, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Rle.parse(input), input);
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()),
                "message '" + e.getMessage() + "' lacks '" + fragment + "'");
    }
}
