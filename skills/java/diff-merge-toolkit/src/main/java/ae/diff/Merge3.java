package ae.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Three-way line merge (diff3 style) built on {@link Diff#regions}.
 *
 * <p>Each side is diffed against the base into change regions. Regions from the two sides are
 * grouped when their base ranges intersect or start at the same base index; a group touched by
 * one side only, or by both sides with identical resulting text, merges cleanly. Ranges that
 * merely touch (one ends where the other starts) do not conflict.
 */
public final class Merge3 {
    private Merge3() {}

    /** A piece of the result covering base lines {@code [baseStart, baseEnd)}. */
    public sealed interface Chunk permits Clean, Conflict {
        int baseStart();

        int baseEnd();
    }

    /** Merged lines without conflict. */
    public record Clean(List<String> lines, int baseStart, int baseEnd) implements Chunk {
        public Clean {
            lines = List.copyOf(lines);
        }
    }

    /** Both sides changed the same base range differently. */
    public record Conflict(List<String> base, List<String> ours, List<String> theirs,
                           int baseStart, int baseEnd) implements Chunk {
        public Conflict {
            base = List.copyOf(base);
            ours = List.copyOf(ours);
            theirs = List.copyOf(theirs);
        }
    }

    /** Merge outcome: chunks tile the base from index 0 to its end, in order. */
    public record Result(List<Chunk> chunks) {
        public Result {
            chunks = List.copyOf(chunks);
        }

        public int conflictCount() {
            int n = 0;
            for (Chunk c : chunks) {
                if (c instanceof Conflict) {
                    n++;
                }
            }
            return n;
        }

        public boolean isClean() {
            return conflictCount() == 0;
        }

        /** Merged text of a clean result; throws {@link IllegalStateException} if there are conflicts. */
        public List<String> lines() {
            if (!isClean()) {
                throw new IllegalStateException(conflictCount() + " unresolved conflict(s)");
            }
            List<String> out = new ArrayList<>();
            for (Chunk c : chunks) {
                out.addAll(((Clean) c).lines());
            }
            return out;
        }

        /** Text with conflict markers; every line is terminated by a newline. */
        public String render(String oursLabel, String theirsLabel) {
            StringBuilder sb = new StringBuilder();
            for (Chunk c : chunks) {
                if (c instanceof Clean k) {
                    k.lines().forEach(l -> sb.append(l).append('\n'));
                } else {
                    Conflict k = (Conflict) c;
                    sb.append("<<<<<<< ").append(oursLabel).append('\n');
                    k.ours().forEach(l -> sb.append(l).append('\n'));
                    sb.append("=======\n");
                    k.theirs().forEach(l -> sb.append(l).append('\n'));
                    sb.append(">>>>>>> ").append(theirsLabel).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private record Tagged(Diff.Region region, boolean ours) {}

    public static Result merge(List<String> base, List<String> ours, List<String> theirs) {
        Objects.requireNonNull(base);
        List<Tagged> all = new ArrayList<>();
        for (Diff.Region r : Diff.regions(base, ours)) {
            all.add(new Tagged(r, true));
        }
        for (Diff.Region r : Diff.regions(base, theirs)) {
            all.add(new Tagged(r, false));
        }
        all.sort(Comparator.comparingInt((Tagged t) -> t.region().aStart())
                .thenComparingInt(t -> t.region().aEnd()));
        List<Chunk> chunks = new ArrayList<>();
        int pos = 0;
        int i = 0;
        while (i < all.size()) {
            int lo = all.get(i).region().aStart();
            int hi = all.get(i).region().aEnd();
            int j = i + 1;
            while (j < all.size() && (all.get(j).region().aStart() < hi || all.get(j).region().aStart() == lo)) {
                hi = Math.max(hi, all.get(j).region().aEnd());
                j++;
            }
            if (lo > pos) {
                chunks.add(new Clean(base.subList(pos, lo), pos, lo));
            }
            List<String> o = applyGroup(base, all.subList(i, j), true, lo, hi);
            List<String> t = applyGroup(base, all.subList(i, j), false, lo, hi);
            if (o.equals(t)) {
                chunks.add(new Clean(o, lo, hi));
            } else if (!touches(all.subList(i, j), false)) {
                chunks.add(new Clean(o, lo, hi));
            } else if (!touches(all.subList(i, j), true)) {
                chunks.add(new Clean(t, lo, hi));
            } else {
                chunks.add(new Conflict(base.subList(lo, hi), o, t, lo, hi));
            }
            pos = hi;
            i = j;
        }
        if (pos < base.size()) {
            chunks.add(new Clean(base.subList(pos, base.size()), pos, base.size()));
        }
        return new Result(chunks);
    }

    private static boolean touches(List<Tagged> group, boolean ours) {
        for (Tagged t : group) {
            if (t.ours() == ours) {
                return true;
            }
        }
        return false;
    }

    /** Text of base range {@code [lo,hi)} after applying one side's regions that lie in the group. */
    private static List<String> applyGroup(List<String> base, List<Tagged> group, boolean ours, int lo, int hi) {
        List<String> out = new ArrayList<>();
        int cursor = lo;
        for (Tagged t : group) {
            if (t.ours() != ours) {
                continue;
            }
            Diff.Region r = t.region();
            out.addAll(base.subList(cursor, r.aStart()));
            out.addAll(r.replacement());
            cursor = r.aEnd();
        }
        out.addAll(base.subList(cursor, hi));
        return out;
    }
}
