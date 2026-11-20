package ae.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShiftPlannerTest {

    private static final String WEEK = """
            # week
            worker ann max 2
            worker bob max 1
            worker cy max 2

            shift mon needs 2
            shift tue needs 2
            shift wed needs 1
            allow ann mon cost 1
            allow ann tue cost 4
            allow bob mon cost 2
            allow bob wed cost 1
            allow cy tue cost 3
            allow cy wed cost 5
            """;

    @Test
    void fullyCoveredWeek() {
        ShiftPlanner.Plan p = ShiftPlanner.plan(WEEK);
        assertEquals(15, p.totalCost());
        assertEquals(
                "ann -> mon\nann -> tue\nbob -> mon\ncy -> tue\ncy -> wed\nunfilled: none\ncost: 15\n", p.format());
    }

    @Test
    void shortageFillsMostSlotsThenCheapest() {
        String text = WEEK.replace("allow cy wed cost 5\n", "");
        ShiftPlanner.Plan p = ShiftPlanner.plan(text);
        assertEquals(9, p.totalCost());
        assertEquals(
                "ann -> mon\nann -> tue\nbob -> wed\ncy -> tue\nunfilled: mon 1\ncost: 9\n", p.format());
        assertEquals(1, p.unfilled().get("mon"));
        assertEquals(1, p.unfilled().size());
    }

    @Test
    void emptyInputGivesEmptyPlan() {
        ShiftPlanner.Plan p = ShiftPlanner.plan("\n# nothing\n\n");
        assertEquals("unfilled: none\ncost: 0\n", p.format());
    }

    @Test
    void workerWithMaxZeroIsNeverUsedAndNegativeCostsArePreferred() {
        ShiftPlanner.Plan p = ShiftPlanner.plan("""
                worker a max 0
                worker b max 1
                shift s needs 1
                allow a s cost -100
                allow b s cost -3
                """);
        assertEquals(List.of(new ShiftPlanner.Assignment("b", "s")), p.assignments());
        assertEquals(-3, p.totalCost());
    }

    @Test
    void shiftsNeedingMoreThanAvailableStayUnfilledInDeclarationOrder() {
        ShiftPlanner.Plan p = ShiftPlanner.plan("""
                worker a max 5
                shift x needs 2
                shift y needs 1
                shift z needs 0
                allow a x cost 1
                allow a y cost 1
                """);
        assertEquals("a -> x\na -> y\nunfilled: x 1\ncost: 2\n", p.format());
    }

    private static void parseError(String text, int line, String fragment) {
        ShiftPlanner.PlanParseException e =
                assertThrows(ShiftPlanner.PlanParseException.class, () -> ShiftPlanner.plan(text), text);
        assertEquals(line, e.line(), text);
        assertTrue(e.getMessage().startsWith("line " + line + ": "), e.getMessage());
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void parseErrorsCarryLineNumbers() {
        parseError("# c\n\nfly ann\n", 3, "unknown directive 'fly'");
        parseError("worker a max 1\nworker a max 2\n", 2, "duplicate worker 'a'");
        parseError("shift s needs 1\nshift s needs 1\n", 2, "duplicate shift 's'");
        parseError("worker a max 1\nallow a s cost 1\n", 2, "unknown shift 's'");
        parseError("shift s needs 1\nallow a s cost 1\n", 2, "unknown worker 'a'");
        parseError("worker a max x\n", 1, "not an integer");
        parseError("worker a max -1\n", 1, "out of range");
        parseError("worker a max 1000001\n", 1, "out of range");
        parseError("shift s needs 1\nworker a max 1\nallow a s cost 1000001\n", 3, "out of range");
        parseError("worker a\n", 1, "expected: worker <name> max <n>");
        parseError("shift s needs 1 2\n", 1, "expected: shift <name> needs <n>");
        parseError("worker a max 1\nshift s needs 1\nallow a s 3\n", 3, "expected: allow <worker> <shift> cost <c>");
        parseError("worker a max 1\nshift s needs 1\nallow a s cost 1\nallow a s cost 2\n", 4, "duplicate allow");
        parseError("worker a mox 1\n", 1, "expected: worker <name> max <n>");
    }

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> ShiftPlanner.plan(null));
    }

    @Test
    void randomInstancesMatchSubsetEnumeration() {
        Random rnd = new Random(31);
        for (int trial = 0; trial < 250; trial++) {
            int w = 1 + rnd.nextInt(3);
            int s = 1 + rnd.nextInt(3);
            int[] max = new int[w];
            int[] need = new int[s];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < w; i++) {
                max[i] = rnd.nextInt(3);
                sb.append("worker w").append(i).append(" max ").append(max[i]).append('\n');
            }
            for (int j = 0; j < s; j++) {
                need[j] = rnd.nextInt(3);
                sb.append("shift s").append(j).append(" needs ").append(need[j]).append('\n');
            }
            List<int[]> allowed = new ArrayList<>(); // {worker, shift, cost}
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < s; j++) {
                    if (rnd.nextInt(3) > 0) {
                        int c = rnd.nextInt(11) - 3;
                        allowed.add(new int[] {i, j, c});
                        sb.append("allow w").append(i).append(" s").append(j).append(" cost ").append(c).append('\n');
                    }
                }
            }
            int bestSize = -1;
            int bestCost = 0;
            for (int mask = 0; mask < (1 << allowed.size()); mask++) {
                int[] wc = new int[w];
                int[] sc = new int[s];
                int size = 0;
                int cost = 0;
                for (int k = 0; k < allowed.size(); k++) {
                    if ((mask >> k & 1) != 0) {
                        int[] a = allowed.get(k);
                        wc[a[0]]++;
                        sc[a[1]]++;
                        size++;
                        cost += a[2];
                    }
                }
                boolean ok = true;
                for (int i = 0; i < w; i++) {
                    ok &= wc[i] <= max[i];
                }
                for (int j = 0; j < s; j++) {
                    ok &= sc[j] <= need[j];
                }
                if (ok && (size > bestSize || (size == bestSize && cost < bestCost))) {
                    bestSize = size;
                    bestCost = cost;
                }
            }
            ShiftPlanner.Plan p = ShiftPlanner.plan(sb.toString());
            String msg = "trial " + trial + "\n" + sb;
            assertEquals(bestSize, p.assignments().size(), msg);
            assertEquals(bestCost, p.totalCost(), msg);
            int[] wc = new int[w];
            int[] sc = new int[s];
            long sum = 0;
            for (ShiftPlanner.Assignment a : p.assignments()) {
                int wi = Integer.parseInt(a.worker().substring(1));
                int sj = Integer.parseInt(a.shift().substring(1));
                wc[wi]++;
                sc[sj]++;
                for (int[] al : allowed) {
                    if (al[0] == wi && al[1] == sj) {
                        sum += al[2];
                    }
                }
            }
            assertEquals(p.totalCost(), sum, msg);
            for (int i = 0; i < w; i++) {
                assertTrue(wc[i] <= max[i], msg);
            }
            int unfilledTotal = 0;
            for (int j = 0; j < s; j++) {
                assertTrue(sc[j] <= need[j], msg);
                int missing = need[j] - sc[j];
                unfilledTotal += missing;
                assertEquals(missing, p.unfilled().getOrDefault("s" + j, 0), msg);
            }
            assertEquals(p.unfilled().values().stream().mapToInt(Integer::intValue).sum(), unfilledTotal, msg);
        }
    }
}
