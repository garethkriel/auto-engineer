package ae.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses a small text spec of workers, shifts and allowed pairings and solves it as a min-cost max-flow:
 * fill as many slots as possible, then minimise total cost.
 *
 * <pre>
 * worker NAME max N
 * shift  NAME needs N
 * allow  WORKER SHIFT cost C
 * </pre>
 */
public final class ShiftPlanner {

    private static final int MAX_COUNT = 1_000_000;
    private static final int MAX_COST = 1_000_000;

    /** One worker covering one slot of one shift. */
    public record Assignment(String worker, String shift) {}

    /** The solved plan. {@code unfilled} maps shift name to missing slots, only for shifts still short. */
    public record Plan(List<Assignment> assignments, Map<String, Integer> unfilled, long totalCost) {
        /** Canonical text rendering. */
        public String format() {
            StringBuilder sb = new StringBuilder();
            for (Assignment a : assignments) {
                sb.append(a.worker()).append(" -> ").append(a.shift()).append('\n');
            }
            if (unfilled.isEmpty()) {
                sb.append("unfilled: none\n");
            } else {
                List<String> parts = new ArrayList<>();
                unfilled.forEach((s, k) -> parts.add(s + " " + k));
                sb.append("unfilled: ").append(String.join(", ", parts)).append('\n');
            }
            return sb.append("cost: ").append(totalCost).append('\n').toString();
        }
    }

    /** Malformed input; {@link #line()} is 1-based. */
    public static final class PlanParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int line;

        PlanParseException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private record Allow(int worker, int shift, int cost) {}

    private ShiftPlanner() {}

    public static Plan plan(String text) {
        Objects.requireNonNull(text, "text");
        Map<String, Integer> workerIndex = new LinkedHashMap<>();
        Map<String, Integer> shiftIndex = new LinkedHashMap<>();
        List<Integer> workerMax = new ArrayList<>();
        List<Integer> shiftNeed = new ArrayList<>();
        List<Allow> allows = new ArrayList<>();
        java.util.Set<Long> seenPairs = new java.util.HashSet<>();

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "worker" -> {
                    if (t.length != 4 || !t[2].equals("max")) {
                        throw new PlanParseException(no, "expected: worker <name> max <n>");
                    }
                    if (workerIndex.containsKey(t[1])) {
                        throw new PlanParseException(no, "duplicate worker '" + t[1] + "'");
                    }
                    workerIndex.put(t[1], workerIndex.size());
                    workerMax.add(number(t[3], 0, MAX_COUNT, no));
                }
                case "shift" -> {
                    if (t.length != 4 || !t[2].equals("needs")) {
                        throw new PlanParseException(no, "expected: shift <name> needs <n>");
                    }
                    if (shiftIndex.containsKey(t[1])) {
                        throw new PlanParseException(no, "duplicate shift '" + t[1] + "'");
                    }
                    shiftIndex.put(t[1], shiftIndex.size());
                    shiftNeed.add(number(t[3], 0, MAX_COUNT, no));
                }
                case "allow" -> {
                    if (t.length != 5 || !t[3].equals("cost")) {
                        throw new PlanParseException(no, "expected: allow <worker> <shift> cost <c>");
                    }
                    Integer w = workerIndex.get(t[1]);
                    if (w == null) {
                        throw new PlanParseException(no, "unknown worker '" + t[1] + "'");
                    }
                    Integer s = shiftIndex.get(t[2]);
                    if (s == null) {
                        throw new PlanParseException(no, "unknown shift '" + t[2] + "'");
                    }
                    int c = number(t[4], -MAX_COST, MAX_COST, no);
                    if (!seenPairs.add((long) w << 32 | s)) {
                        throw new PlanParseException(no, "duplicate allow " + t[1] + " " + t[2]);
                    }
                    allows.add(new Allow(w, s, c));
                }
                default -> throw new PlanParseException(no, "unknown directive '" + t[0] + "'");
            }
        }
        return solve(new ArrayList<>(workerIndex.keySet()), new ArrayList<>(shiftIndex.keySet()), workerMax, shiftNeed, allows);
    }

    private static int number(String token, int min, int max, int line) {
        long v;
        try {
            v = Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new PlanParseException(line, "not an integer: '" + token + "'");
        }
        if (v < min || v > max) {
            throw new PlanParseException(line, "number out of range [" + min + "," + max + "]: " + v);
        }
        return (int) v;
    }

    private static Plan solve(
            List<String> workers, List<String> shifts, List<Integer> workerMax, List<Integer> shiftNeed, List<Allow> allows) {
        int w = workers.size();
        int s = shifts.size();
        int source = 0;
        int sink = w + s + 1;
        FlowNetwork g = new FlowNetwork(w + s + 2);
        for (int i = 0; i < w; i++) {
            g.addEdge(source, 1 + i, workerMax.get(i), 0);
        }
        int[] allowEdge = new int[allows.size()];
        for (int k = 0; k < allows.size(); k++) {
            Allow a = allows.get(k);
            allowEdge[k] = g.addEdge(1 + a.worker(), 1 + w + a.shift(), 1, a.cost());
        }
        int[] shiftEdge = new int[s];
        for (int j = 0; j < s; j++) {
            shiftEdge[j] = g.addEdge(1 + w + j, sink, shiftNeed.get(j), 0);
        }
        FlowNetwork.FlowResult r = g.minCostFlow(source, sink, Long.MAX_VALUE);

        // Order assignments by worker declaration, then shift declaration.
        List<int[]> chosen = new ArrayList<>();
        for (int k = 0; k < allows.size(); k++) {
            if (g.flow(allowEdge[k]) > 0) {
                chosen.add(new int[] {allows.get(k).worker(), allows.get(k).shift()});
            }
        }
        chosen.sort((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(x[1], y[1]));
        List<Assignment> out = new ArrayList<>();
        for (int[] c : chosen) {
            out.add(new Assignment(workers.get(c[0]), shifts.get(c[1])));
        }
        Map<String, Integer> unfilled = new LinkedHashMap<>();
        for (int j = 0; j < s; j++) {
            long missing = shiftNeed.get(j) - g.flow(shiftEdge[j]);
            if (missing > 0) {
                unfilled.put(shifts.get(j), (int) missing);
            }
        }
        return new Plan(List.copyOf(out), unfilled, r.cost());
    }
}
