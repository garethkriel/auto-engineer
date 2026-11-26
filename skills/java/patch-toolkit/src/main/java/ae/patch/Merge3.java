package ae.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Three-way line merge in the style of {@code diff3 -m}. */
public final class Merge3 {
    private Merge3() { }

    /** A piece of merged output. */
    public sealed interface Chunk {
        /** Text merged without conflict. */
        record Stable(List<String> lines) implements Chunk { }

        /** Both sides changed the same base span differently. */
        record Conflict(List<String> ours, List<String> base, List<String> theirs) implements Chunk { }
    }

    /** A change against the base: base[start,end) is replaced by {@code lines}. */
    private record Region(int start, int end, List<String> lines) { }

    private static List<Region> regions(List<String> base, List<String> side) {
        List<Region> out = new ArrayList<>();
        int bi = 0;
        List<Edit> script = LineDiff.diff(base, side);
        int i = 0;
        while (i < script.size()) {
            if (script.get(i) instanceof Edit.Keep) {
                bi++;
                i++;
                continue;
            }
            int start = bi;
            List<String> repl = new ArrayList<>();
            while (i < script.size() && !(script.get(i) instanceof Edit.Keep)) {
                if (script.get(i) instanceof Edit.Delete) {
                    bi++;
                } else {
                    repl.add(script.get(i).line());
                }
                i++;
            }
            out.add(new Region(start, bi, repl));
        }
        return out;
    }

    /** Merges; regions of the two sides that overlap or touch in the base form one cluster. */
    public static List<Chunk> merge(List<String> base, List<String> ours, List<String> theirs) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(ours, "ours");
        Objects.requireNonNull(theirs, "theirs");
        List<Region> o = regions(base, ours);
        List<Region> t = regions(base, theirs);
        List<Chunk> out = new ArrayList<>();
        int pos = 0;
        int oi = 0;
        int ti = 0;
        while (oi < o.size() || ti < t.size()) {
            Region first = nextRegion(o, oi, t, ti);
            int cs = first.start();
            int ce = first.end();
            int oFrom = oi;
            int tFrom = ti;
            boolean grew = true;
            while (grew) {
                grew = false;
                while (oi < o.size() && o.get(oi).start() <= ce) {
                    ce = Math.max(ce, o.get(oi).end());
                    oi++;
                    grew = true;
                }
                while (ti < t.size() && t.get(ti).start() <= ce) {
                    ce = Math.max(ce, t.get(ti).end());
                    ti++;
                    grew = true;
                }
            }
            addStable(out, base.subList(pos, cs));
            List<String> oursText = apply(base, cs, ce, o.subList(oFrom, oi));
            List<String> theirsText = apply(base, cs, ce, t.subList(tFrom, ti));
            if (oFrom == oi) {
                addStable(out, theirsText);
            } else if (tFrom == ti || oursText.equals(theirsText)) {
                addStable(out, oursText);
            } else {
                out.add(new Chunk.Conflict(oursText, List.copyOf(base.subList(cs, ce)), theirsText));
            }
            pos = ce;
        }
        addStable(out, base.subList(pos, base.size()));
        return out;
    }

    private static Region nextRegion(List<Region> o, int oi, List<Region> t, int ti) {
        if (oi >= o.size()) {
            return t.get(ti);
        }
        if (ti >= t.size()) {
            return o.get(oi);
        }
        return o.get(oi).start() <= t.get(ti).start() ? o.get(oi) : t.get(ti);
    }

    private static List<String> apply(List<String> base, int cs, int ce, List<Region> regions) {
        List<String> out = new ArrayList<>();
        int cur = cs;
        for (Region r : regions) {
            out.addAll(base.subList(cur, r.start()));
            out.addAll(r.lines());
            cur = r.end();
        }
        out.addAll(base.subList(cur, ce));
        return out;
    }

    private static void addStable(List<Chunk> out, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (!out.isEmpty() && out.get(out.size() - 1) instanceof Chunk.Stable prev) {
            List<String> joined = new ArrayList<>(prev.lines());
            joined.addAll(lines);
            out.set(out.size() - 1, new Chunk.Stable(joined));
        } else {
            out.add(new Chunk.Stable(List.copyOf(lines)));
        }
    }

    /** True when any chunk is a conflict. */
    public static boolean hasConflicts(List<Chunk> chunks) {
        return chunks.stream().anyMatch(c -> c instanceof Chunk.Conflict);
    }

    /** Text with conflict markers: ours, a {@code |||||||} base section, then theirs. */
    public static String render(List<Chunk> chunks, String oursLabel, String baseLabel, String theirsLabel) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c instanceof Chunk.Stable s) {
                s.lines().forEach(l -> sb.append(l).append('\n'));
            } else if (c instanceof Chunk.Conflict k) {
                sb.append("<<<<<<< ").append(oursLabel).append('\n');
                k.ours().forEach(l -> sb.append(l).append('\n'));
                sb.append("||||||| ").append(baseLabel).append('\n');
                k.base().forEach(l -> sb.append(l).append('\n'));
                sb.append("=======\n");
                k.theirs().forEach(l -> sb.append(l).append('\n'));
                sb.append(">>>>>>> ").append(theirsLabel).append('\n');
            }
        }
        return sb.toString();
    }
}
