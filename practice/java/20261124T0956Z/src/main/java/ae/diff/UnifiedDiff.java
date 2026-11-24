package ae.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats and parses unified diffs (line model: no "no newline at end of file" support). */
public final class UnifiedDiff {
    private UnifiedDiff() {}

    private static final Pattern HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** Renders the difference between {@code a} and {@code b} with {@code context} lines around changes. */
    public static String format(String oldName, String newName, List<String> a, List<String> b, int context) {
        if (context < 0) {
            throw new IllegalArgumentException("context must be >= 0");
        }
        List<Diff.Edit> edits = Diff.diff(a, b);
        int total = edits.size();
        // aBefore[i] / bBefore[i]: old/new lines consumed before edit i
        int[] aBefore = new int[total + 1];
        int[] bBefore = new int[total + 1];
        for (int i = 0; i < total; i++) {
            Diff.Op op = edits.get(i).op();
            aBefore[i + 1] = aBefore[i] + (op != Diff.Op.INSERT ? 1 : 0);
            bBefore[i + 1] = bBefore[i] + (op != Diff.Op.DELETE ? 1 : 0);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(oldName).append('\n').append("+++ ").append(newName).append('\n');
        List<Integer> changes = new ArrayList<>();
        for (int k = 0; k < total; k++) {
            if (edits.get(k).op() != Diff.Op.KEEP) {
                changes.add(k);
            }
        }
        int c = 0;
        while (c < changes.size()) {
            int first = changes.get(c);
            int last = first;
            c++;
            // changes separated by at most 2*context kept lines share one hunk
            while (c < changes.size() && changes.get(c) - last - 1 <= 2 * context) {
                last = changes.get(c);
                c++;
            }
            int from = Math.max(0, first - context);
            int to = Math.min(total, last + 1 + context);
            int oldLen = aBefore[to] - aBefore[from];
            int newLen = bBefore[to] - bBefore[from];
            sb.append("@@ -").append(range(aBefore[from], oldLen))
                    .append(" +").append(range(bBefore[from], newLen)).append(" @@\n");
            for (int k = from; k < to; k++) {
                Diff.Edit e = edits.get(k);
                char tag = e.op() == Diff.Op.KEEP ? ' ' : e.op() == Diff.Op.DELETE ? '-' : '+';
                sb.append(tag).append(e.line()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String range(int consumedBefore, int len) {
        if (len == 0) {
            return consumedBefore + ",0";
        }
        int start = consumedBefore + 1;
        return len == 1 ? Integer.toString(start) : start + "," + len;
    }

    /** Parses one file's unified diff; throws {@link PatchException} on any inconsistency. */
    public static Patch parse(String text) {
        Objects.requireNonNull(text);
        List<String> ls = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) {
            ls.remove(ls.size() - 1);
        }
        if (ls.size() < 2 || !ls.get(0).startsWith("--- ") || !ls.get(1).startsWith("+++ ")) {
            throw new PatchException("missing ---/+++ header");
        }
        String oldName = name(ls.get(0).substring(4));
        String newName = name(ls.get(1).substring(4));
        int i = 2;
        List<Patch.Hunk> hunks = new ArrayList<>();
        while (i < ls.size()) {
            Matcher m = HEADER.matcher(ls.get(i));
            if (!m.matches()) {
                throw new PatchException("line " + (i + 1) + ": expected hunk header, got \"" + ls.get(i) + "\"");
            }
            int oldStart = Integer.parseInt(m.group(1));
            int oldLen = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newLen = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            i++;
            int oldSeen = 0;
            int newSeen = 0;
            List<Patch.Line> body = new ArrayList<>();
            while (oldSeen < oldLen || newSeen < newLen) {
                if (i >= ls.size()) {
                    throw new PatchException("hunk truncated: header promises " + oldLen + "/" + newLen
                            + " lines but only " + oldSeen + "/" + newSeen + " found");
                }
                String l = ls.get(i);
                if (l.startsWith("\\")) {
                    i++;
                    continue;
                }
                char tag = l.isEmpty() ? ' ' : l.charAt(0);
                String content = l.isEmpty() ? "" : l.substring(1);
                if (tag != ' ' && tag != '-' && tag != '+') {
                    throw new PatchException("line " + (i + 1) + ": bad hunk line \"" + l + "\"");
                }
                if (tag != '+') {
                    oldSeen++;
                }
                if (tag != '-') {
                    newSeen++;
                }
                if (oldSeen > oldLen || newSeen > newLen) {
                    throw new PatchException("line " + (i + 1) + ": hunk has more lines than its header declares");
                }
                body.add(new Patch.Line(tag, content));
                i++;
            }
            while (i < ls.size() && ls.get(i).startsWith("\\")) {
                i++;
            }
            hunks.add(new Patch.Hunk(oldStart, oldLen, newStart, newLen, body));
        }
        return new Patch(oldName, newName, hunks);
    }

    private static String name(String raw) {
        int tab = raw.indexOf('\t');
        return tab >= 0 ? raw.substring(0, tab) : raw;
    }
}
