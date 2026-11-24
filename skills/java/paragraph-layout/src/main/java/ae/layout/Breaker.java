package ae.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Optimal line breaking by dynamic programming over active nodes (total-fit, no fitness classes). */
public final class Breaker {
    private Breaker() {}

    private record Key(int index, int line, boolean flagged) {}

    private static final class Node {
        final int index;
        final int line;
        final boolean flagged;
        final long demerits;
        final Node prev;
        final Layout.Line shape;

        Node(int index, int line, boolean flagged, long demerits, Node prev, Layout.Line shape) {
            this.index = index;
            this.line = line;
            this.flagged = flagged;
            this.demerits = demerits;
            this.prev = prev;
            this.shape = shape;
        }
    }

    /**
     * Finds the break list with the least total demerits.
     *
     * @param items     the paragraph; must end with a forced break
     * @param lineWidth target width of a line, by zero-based line index
     * @param params    penalties and tolerance
     * @throws LayoutException if no layout is feasible
     */
    public static Layout breakLines(List<Item> items, IntUnaryOperator lineWidth, Params params)
            throws LayoutException {
        return breakLines(items, lineWidth, Integer.MAX_VALUE, params);
    }

    /** Constant line width: every line has the same target, so nodes are merged across line numbers. */
    public static Layout breakLines(List<Item> items, int width, Params params) throws LayoutException {
        return breakLines(items, line -> width, 0, params);
    }

    /**
     * Like {@link #breakLines(List, IntUnaryOperator, Params)}, but promises that {@code lineWidth(k)}
     * is the same for every {@code k >= uniformFromLine}. Line numbers from there on are merged, which
     * keeps the active set small (otherwise it is keyed by every reachable line count).
     */
    public static Layout breakLines(List<Item> items, IntUnaryOperator lineWidth, int uniformFromLine,
                                    Params params) throws LayoutException {
        if (uniformFromLine < 0) {
            throw new IllegalArgumentException("uniformFromLine must not be negative");
        }
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(lineWidth, "lineWidth");
        Objects.requireNonNull(params, "params");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (!isForced(items.get(items.size() - 1))) {
            throw new IllegalArgumentException("last item must be a forced break");
        }
        int n = items.size();
        long[] pw = new long[n + 1];
        long[] py = new long[n + 1];
        long[] pz = new long[n + 1];
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            pw[i + 1] = pw[i];
            py[i + 1] = py[i];
            pz[i + 1] = pz[i];
            if (it instanceof Item.Box b) {
                pw[i + 1] += b.width();
            } else if (it instanceof Item.Glue g) {
                pw[i + 1] += g.width();
                py[i + 1] += g.stretch();
                pz[i + 1] += g.shrink();
            }
        }
        int[] nextBox = new int[n + 2];
        nextBox[n] = n;
        nextBox[n + 1] = n;
        for (int i = n - 1; i >= 0; i--) {
            nextBox[i] = items.get(i) instanceof Item.Box ? i : nextBox[i + 1];
        }

        Map<Key, Node> active = new LinkedHashMap<>();
        Node start = new Node(-1, 0, false, 0, null, null);
        active.put(new Key(-1, 0, false), start);
        int peak = 1;

        for (int i = 0; i < n; i++) {
            Item item = items.get(i);
            if (!isLegalBreak(items, i)) {
                continue;
            }
            int pen = 0;
            int penWidth = 0;
            boolean flagged = false;
            if (item instanceof Item.Penalty p) {
                pen = p.penalty();
                penWidth = p.width();
                flagged = p.flagged();
            }
            Map<Key, Node> fresh = new LinkedHashMap<>();
            for (Iterator<Node> it = active.values().iterator(); it.hasNext();) {
                Node a = it.next();
                int s = Math.min(nextBox[a.index + 1], i);
                long w = pw[i] - pw[s];
                long y = py[i] - py[s];
                long z = pz[i] - pz[s];
                int target = lineWidth.applyAsInt(a.line);
                if (target < 0) {
                    throw new IllegalArgumentException("line width must not be negative: line " + a.line);
                }
                if (w - z > target) {
                    // Natural width minus all shrink only grows with more items: dead for good.
                    it.remove();
                    continue;
                }
                long natural = w + penWidth;
                long badness = badness(target - natural, y, z);
                if (badness < 0 || badness > params.tolerance()) {
                    continue;
                }
                long demerits = (params.linePenalty() + badness) * (params.linePenalty() + badness);
                if (pen >= 0) {
                    demerits += (long) pen * pen;
                } else if (pen > -Item.PENALTY_INF) {
                    demerits -= (long) pen * pen;
                }
                if (flagged && a.flagged) {
                    demerits += params.flaggedDemerits();
                }
                long total = a.demerits + demerits;
                int nextLine = Math.min(a.line + 1, uniformFromLine);
                Key key = new Key(i, nextLine, flagged);
                Node old = fresh.get(key);
                if (old == null || total < old.demerits) {
                    Layout.Line shape = new Layout.Line(s, i, natural, target, y, z, badness, demerits);
                    fresh.put(key, new Node(i, nextLine, flagged, total, a, shape));
                }
            }
            if (isForced(item)) {
                active.clear();
                if (fresh.isEmpty()) {
                    throw new LayoutException("no feasible layout: forced break at item " + i
                            + " cannot be reached within tolerance " + params.tolerance());
                }
            }
            active.putAll(fresh);
            peak = Math.max(peak, active.size());
        }

        Node best = null;
        for (Node node : active.values()) {
            if (node.index == n - 1 && (best == null || node.demerits < best.demerits)) {
                best = node;
            }
        }
        if (best == null) {
            throw new LayoutException("no feasible layout within tolerance " + params.tolerance());
        }
        List<Integer> breaks = new ArrayList<>();
        List<Layout.Line> lines = new ArrayList<>();
        for (Node node = best; node.prev != null; node = node.prev) {
            breaks.add(node.index);
            lines.add(node.shape);
        }
        Collections.reverse(breaks);
        Collections.reverse(lines);
        return new Layout(List.copyOf(breaks), List.copyOf(lines), best.demerits, peak);
    }

    /** Badness of a line that is {@code diff} short (positive) or long, or -1 if infeasible. */
    private static long badness(long diff, long stretch, long shrink) {
        if (diff == 0) {
            return 0;
        }
        long room = diff > 0 ? stretch : shrink;
        long gap = Math.abs(diff);
        if (room <= 0 || gap > room && diff < 0) {
            return -1;
        }
        long cube;
        try {
            cube = Math.multiplyExact(100L, Math.multiplyExact(gap, Math.multiplyExact(gap, gap)));
        } catch (ArithmeticException tooBig) {
            return -1;
        }
        long denom;
        try {
            denom = Math.multiplyExact(room, Math.multiplyExact(room, room));
        } catch (ArithmeticException huge) {
            return 0;
        }
        long b = cube / denom;
        return b > Params.MAX_TOLERANCE ? -1 : b;
    }

    private static boolean isForced(Item it) {
        return it instanceof Item.Penalty p && p.penalty() <= -Item.PENALTY_INF;
    }

    private static boolean isLegalBreak(List<Item> items, int i) {
        Item it = items.get(i);
        if (it instanceof Item.Glue) {
            return i > 0 && items.get(i - 1) instanceof Item.Box;
        }
        return it instanceof Item.Penalty p && p.penalty() < Item.PENALTY_INF;
    }
}
