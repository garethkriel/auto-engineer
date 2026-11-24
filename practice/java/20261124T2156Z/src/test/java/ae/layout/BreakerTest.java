package ae.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BreakerTest {
    private static final Params P = new Params(10, 3000, 1_000_000);

    private static IntUnaryOperator fixed(int w) {
        return line -> w;
    }

    private static Item.Box box(int w) {
        return new Item.Box(w, "x".repeat(w));
    }

    private static Item.Glue sp() {
        return new Item.Glue(1, 1, 0);
    }

    private static void ending(List<Item> items) {
        items.add(new Item.Penalty(0, Item.PENALTY_INF, false));
        items.add(new Item.Glue(0, 100_000, 0));
        items.add(new Item.Penalty(0, -Item.PENALTY_INF, false));
    }

    /** 0 B3, 1 G, 2 B3, 3 G, 4 B3, 5 G, 6 B3, 7 P(INF), 8 G fill, 9 P(-INF). */
    private static List<Item> fourWords() {
        List<Item> items = new ArrayList<>(List.of(box(3), sp(), box(3), sp(), box(3), sp(), box(3)));
        ending(items);
        return items;
    }

    @Test
    void fourWordsWidthTenHasOnlyTwoPlusTwo() throws LayoutException {
        // Three words need 11 > 10 with shrink 0. Line 1: W=7, d=3, Y=1 -> badness 100*27=2700,
        // demerits (10+2700)^2 = 7344100. Last line: badness 0 -> 100. Total 7344200.
        Layout l = Breaker.breakLines(fourWords(), fixed(10), P);
        assertEquals(List.of(3, 9), l.breaks());
        assertEquals(7_344_200L, l.totalDemerits());
        assertEquals(2, l.lines().size());
        assertEquals(2700L, l.lines().get(0).badness());
        assertEquals(7, l.lines().get(0).naturalWidth());
        assertEquals(0, l.lines().get(0).from());
        assertEquals(3, l.lines().get(0).to());
        assertEquals(4, l.lines().get(1).from());
    }

    @Test
    void toleranceBoundaryIsInclusive() throws LayoutException {
        assertThrows(LayoutException.class,
                () -> Breaker.breakLines(fourWords(), fixed(10), new Params(10, 3000, 2699)));
        Layout l = Breaker.breakLines(fourWords(), fixed(10), new Params(10, 3000, 2700));
        assertEquals(List.of(3, 9), l.breaks());
    }

    @Test
    void forcedBreakEndsLineEvenWhenMoreFits() throws LayoutException {
        // 0 B3, 1 G, 2 B3, 3 P(-INF), 4 B3, 5 G, 6 B3, 7 P(INF), 8 G fill, 9 P(-INF); width 7
        List<Item> items = new ArrayList<>(List.of(box(3), sp(), box(3),
                new Item.Penalty(0, -Item.PENALTY_INF, false), box(3), sp(), box(3)));
        ending(items);
        Layout l = Breaker.breakLines(items, fixed(7), P);
        assertEquals(List.of(3, 9), l.breaks());
        assertEquals(200L, l.totalDemerits());
    }

    @Test
    void glueRightAfterABreakIsDiscarded() throws LayoutException {
        // 0 B4, 1 P(-INF), 2 G(5,0,0), 3 B4, 4 P(INF), 5 G fill, 6 P(-INF); width 4.
        // The wide glue after the forced break belongs to no line, so line 2 is just B4 (d=0).
        List<Item> items = new ArrayList<>(List.of(box(4), new Item.Penalty(0, -Item.PENALTY_INF, false),
                new Item.Glue(5, 0, 0), box(4)));
        ending(items);
        Layout l = Breaker.breakLines(items, fixed(4), P);
        assertEquals(List.of(1, 6), l.breaks());
        assertEquals(3, l.lines().get(1).from());
        assertEquals(200L, l.totalDemerits());
    }

    @Test
    void hyphenBreakAddsWidthAndPenaltyDemerits() throws LayoutException {
        // 0 B2, 1 P(1,50,flag), 2 B2, 3 P(INF), 4 G fill, 5 P(-INF); width 3.
        // Line 1 "ab-": W=2+1=3, badness 0 -> (10)^2 + 50^2 = 2600. Last line W=2 -> 100.
        List<Item> items = new ArrayList<>(List.of(box(2), new Item.Penalty(1, 50, true), box(2)));
        ending(items);
        Layout l = Breaker.breakLines(items, fixed(3), P);
        assertEquals(List.of(1, 5), l.breaks());
        assertEquals(2700L, l.totalDemerits());
        assertEquals(3, l.lines().get(0).naturalWidth());
    }

    @Test
    void negativePenaltySubtractsItsSquare() throws LayoutException {
        // Same as above with penalty -10: (10)^2 - 10^2 = 0, plus last line 100.
        List<Item> items = new ArrayList<>(List.of(box(2), new Item.Penalty(1, -10, false), box(2)));
        ending(items);
        Layout l = Breaker.breakLines(items, fixed(3), P);
        assertEquals(List.of(1, 5), l.breaks());
        assertEquals(100L, l.totalDemerits());
    }

    @Test
    void consecutiveFlaggedBreaksAreCharged() throws LayoutException {
        // 0 B2, 1 P flag, 2 B2, 3 P flag, 4 B2, 5 P(INF), 6 G fill, 7 P(-INF); width 3.
        // Lines "ab-", "cd-", "ef": 2600 + (2600 + 3000) + 100 = 8300; without the surcharge 5300.
        List<Item> items = new ArrayList<>(List.of(box(2), new Item.Penalty(1, 50, true), box(2),
                new Item.Penalty(1, 50, true), box(2)));
        ending(items);
        assertEquals(8300L, Breaker.breakLines(items, fixed(3), P).totalDemerits());
        assertEquals(5300L, Breaker.breakLines(items, fixed(3), new Params(10, 0, 1_000_000)).totalDemerits());
        assertEquals(List.of(1, 3, 7), Breaker.breakLines(items, fixed(3), P).breaks());
    }

    @Test
    void lineWidthFunctionShapesTheParagraph() throws LayoutException {
        // 0 B2, 1 G, 2 B2, 3 G, 4 B2, 5 P(INF), 6 G fill, 7 P(-INF)
        List<Item> items = new ArrayList<>(List.of(box(2), sp(), box(2), sp(), box(2)));
        ending(items);
        // First line 5 wide, the rest 8: "aa bb" (d=0) then "cc" -> 100 + 100.
        Layout shaped = Breaker.breakLines(items, line -> line == 0 ? 5 : 8, P);
        assertEquals(List.of(3, 7), shaped.breaks());
        assertEquals(200L, shaped.totalDemerits());
        // Constant 8: everything on one (last) line, d=0 -> 100.
        Layout flat = Breaker.breakLines(items, fixed(8), P);
        assertEquals(List.of(7), flat.breaks());
        assertEquals(100L, flat.totalDemerits());
    }

    @Test
    void shrinkIsUsedForOverfullLinesButOnlyWithinLimit() throws LayoutException {
        // B3 G(2,0,1) B3 P(INF) G fill P(-INF). Natural width 8.
        List<Item> items = new ArrayList<>(List.of(box(3), new Item.Glue(2, 0, 1), box(3)));
        ending(items);
        // width 7: d=-1, Z=1 -> badness 100 -> (10+100)^2 = 12100.
        Layout l = Breaker.breakLines(items, fixed(7), P);
        assertEquals(12_100L, l.totalDemerits());
        assertEquals(100L, l.lines().get(0).badness());
        // width 6: d=-2 > shrink 1 -> no layout.
        LayoutException ex = assertThrows(LayoutException.class, () -> Breaker.breakLines(items, fixed(6), P));
        assertTrue(ex.getMessage().contains("no feasible layout"), ex.getMessage());
    }

    @Test
    void validationMessages() {
        List<Item> noEnd = List.of(box(1));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Breaker.breakLines(noEnd, fixed(5), P)).getMessage().contains("forced break"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Item.Glue(1, 1, 2)).getMessage().contains("shrink"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Item.Box(-1, "")).getMessage().contains("width"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Breaker.breakLines(List.of(), fixed(5), P)).getMessage().contains("empty"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new Params(10, 3000, Params.MAX_TOLERANCE + 1)).getMessage().contains("tolerance"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Breaker.breakLines(fourWords(), line -> -1, P)).getMessage().contains("negative"));
    }

    @Test
    void uniformFromLineMatchesTheGeneralSearch() throws LayoutException {
        Random rnd = new Random(5);
        for (int iter = 0; iter < 200; iter++) {
            List<Item> items = new ArrayList<>();
            int words = 3 + rnd.nextInt(30);
            for (int i = 0; i < words; i++) {
                if (i > 0) {
                    items.add(new Item.Glue(2, 1 + rnd.nextInt(2), rnd.nextInt(3)));
                }
                items.add(box(1 + rnd.nextInt(5)));
            }
            ending(items);
            int first = 14 + rnd.nextInt(6);
            int rest = 20 + rnd.nextInt(10);
            IntUnaryOperator shape = line -> line == 0 ? first : rest;
            Params params = new Params(10, 3000, Params.MAX_TOLERANCE);
            Layout general = Breaker.breakLines(items, shape, params);
            Layout merged = Breaker.breakLines(items, shape, 1, params);
            assertEquals(general.totalDemerits(), merged.totalDemerits(), "iter " + iter);
            assertEquals(general.breaks(), merged.breaks(), "iter " + iter);
            assertTrue(merged.peakActiveNodes() <= general.peakActiveNodes());
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Breaker.breakLines(fourWords(), fixed(10), -1, P)).getMessage().contains("uniformFromLine"));
    }

    @Test
    void layoutIsDeterministicAndTiesPreferTheFirstFoundBreakList() throws LayoutException {
        Layout a = Breaker.breakLines(fourWords(), fixed(10), P);
        Layout b = Breaker.breakLines(fourWords(), fixed(10), P);
        assertEquals(a, b);
    }

    @Test
    @Timeout(20)
    void pruningKeepsActiveSetSmallOnLargeParagraphs() throws LayoutException {
        Random rnd = new Random(7);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            if (i > 0) {
                items.add(new Item.Glue(1, 1, 1));
            }
            items.add(box(1 + rnd.nextInt(8)));
        }
        ending(items);
        Layout l = Breaker.breakLines(items, 60, new Params(10, 3000, 1_000_000));
        assertTrue(l.lines().size() > 1000, "lines " + l.lines().size());
        assertTrue(l.peakActiveNodes() < 60, "peak " + l.peakActiveNodes());
        for (Layout.Line line : l.lines().subList(0, l.lines().size() - 1)) {
            assertTrue(line.naturalWidth() - line.shrink() <= 60 && line.naturalWidth() + line.stretch() >= 60);
        }
    }
}
