package ae.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class BreakerOracleTest {
    /** Random paragraph with at most 15 legal breakpoints so the oracle stays cheap. */
    private static List<Item> generate(Random rnd) {
        while (true) {
            List<Item> items = new ArrayList<>();
            int words = 3 + rnd.nextInt(6);
            for (int w = 0; w < words; w++) {
                if (w > 0) {
                    int r = rnd.nextInt(100);
                    if (r < 5) {
                        items.add(new Item.Penalty(0, -Item.PENALTY_INF, false));
                    } else {
                        int width = 1 + rnd.nextInt(3);
                        int stretch = rnd.nextInt(8) == 0 ? 0 : 1 + rnd.nextInt(3);
                        items.add(new Item.Glue(width, stretch, rnd.nextInt(width + 1)));
                    }
                }
                int pieces = 1 + (rnd.nextInt(4) == 0 ? 1 + rnd.nextInt(2) : 0);
                for (int k = 0; k < pieces; k++) {
                    if (k > 0) {
                        int pen = rnd.nextInt(6) == 0 ? -(1 + rnd.nextInt(30)) : rnd.nextInt(80);
                        items.add(new Item.Penalty(rnd.nextInt(2), pen, rnd.nextBoolean()));
                    }
                    items.add(new Item.Box(1 + rnd.nextInt(5), "b"));
                }
            }
            items.add(new Item.Penalty(0, Item.PENALTY_INF, false));
            items.add(new Item.Glue(0, 100_000, 0));
            items.add(new Item.Penalty(0, -Item.PENALTY_INF, false));
            if (Oracle.legalBreaks(items).size() <= 15) {
                return items;
            }
        }
    }

    @Test
    void agreesWithBruteForceOnRandomInstances() throws LayoutException {
        Random rnd = new Random(20261124);
        long[] tolerances = {Params.MAX_TOLERANCE, 200, 50};
        int feasible = 0;
        int infeasible = 0;
        int hyphenated = 0;
        int forcedMid = 0;
        for (int iter = 0; iter < 1500; iter++) {
            List<Item> items = generate(rnd);
            int base = 8 + rnd.nextInt(10);
            int shift = rnd.nextInt(6) - 2;
            IntUnaryOperator width = line -> base + (line % 3 == 0 ? shift : 0);
            Params params = new Params(rnd.nextInt(20), rnd.nextBoolean() ? 3000 : 0,
                    tolerances[iter % tolerances.length]);
            OptionalLong expected = Oracle.best(items, width, params);
            String ctx = "iter " + iter + " " + items + " base=" + base + " shift=" + shift + " " + params;
            if (expected.isEmpty()) {
                infeasible++;
                try {
                    Layout l = Breaker.breakLines(items, width, params);
                    fail("expected no layout but got " + l + " for " + ctx);
                } catch (LayoutException expectedFailure) {
                    // oracle agrees there is no layout
                }
            } else {
                feasible++;
                Layout l = Breaker.breakLines(items, width, params);
                assertEquals(expected.getAsLong(), l.totalDemerits(), ctx);
                OptionalLong again = Oracle.cost(items, width, params, l.breaks());
                assertTrue(again.isPresent(), "returned breaks infeasible: " + ctx);
                assertEquals(expected.getAsLong(), again.getAsLong(), ctx);
                for (int b : l.breaks()) {
                    if (items.get(b) instanceof Item.Penalty p && p.flagged()) {
                        hyphenated++;
                    }
                    if (Oracle.forced(items.get(b)) && b != items.size() - 1) {
                        forcedMid++;
                    }
                }
                long sum = 0;
                for (Layout.Line line : l.lines()) {
                    sum += line.demerits();
                }
                assertEquals(l.totalDemerits(), sum, ctx);
            }
        }
        // Outcome mix measured on this seed: all branches are exercised.
        System.out.println("oracle mix feasible=" + feasible + " infeasible=" + infeasible
                + " hyphenated=" + hyphenated + " forcedMid=" + forcedMid);
        assertTrue(feasible > 400, "feasible " + feasible);
        assertTrue(infeasible > 100, "infeasible " + infeasible);
        assertTrue(hyphenated > 30, "hyphenated " + hyphenated);
        assertTrue(forcedMid > 20, "forcedMid " + forcedMid);
    }
}
