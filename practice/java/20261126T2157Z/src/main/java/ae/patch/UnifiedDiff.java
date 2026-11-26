package ae.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writer and strict parser for single-file unified diffs (GNU {@code diff -u} layout). */
public final class UnifiedDiff {
    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private UnifiedDiff() { }

    /** Unified diff text of {@code a} to {@code b}; empty when equal. */
    public static String format(List<String> a, List<String> b, int context, String oldLabel, String newLabel) {
        if (context < 0) {
            throw new IllegalArgumentException("context must be >= 0");
        }
        List<Edit> script = LineDiff.diff(a, b);
        List<Hunk> hunks = hunks(script, context);
        if (hunks.isEmpty()) {
            return "";
        }
        return render(new Patch(oldLabel, newLabel, hunks));
    }

    /** Hunks of an edit script, merging changes whose gap of kept lines is at most 2*context. */
    static List<Hunk> hunks(List<Edit> script, int context) {
        List<Hunk> out = new ArrayList<>();
        int n = script.size();
        int i = 0;
        int lastEnd = 0; // script index where the previous hunk ended
        int oldNo = 1; // 1-based old line number of script[i]
        int newNo = 1;
        while (i < n) {
            if (script.get(i) instanceof Edit.Keep) {
                i++;
                oldNo++;
                newNo++;
                continue;
            }
            // first change at i: hunk starts `context` kept lines earlier (bounded by previous hunk end)
            int start = i;
            int back = 0;
            while (start > 0 && back < context && script.get(start - 1) instanceof Edit.Keep
                && start - 1 >= lastEnd) {
                start--;
                back++;
            }
            int hOld = oldNo - back;
            int hNew = newNo - back;
            // extend: walk forward until a run of > 2*context keeps (or end)
            int end = i;
            int lastChange = i;
            int j = i;
            while (j < n) {
                if (!(script.get(j) instanceof Edit.Keep)) {
                    lastChange = j;
                } else if (j - lastChange > 2 * context) {
                    break;
                }
                j++;
            }
            end = Math.min(n, lastChange + 1 + context);
            List<String> body = new ArrayList<>();
            int oc = 0;
            int nc = 0;
            for (int k = start; k < end; k++) {
                Edit e = script.get(k);
                if (e instanceof Edit.Keep) {
                    body.add(" " + e.line());
                    oc++;
                    nc++;
                } else if (e instanceof Edit.Delete) {
                    body.add("-" + e.line());
                    oc++;
                } else {
                    body.add("+" + e.line());
                    nc++;
                }
            }
            out.add(new Hunk(oc == 0 ? hOld - 1 : hOld, oc, nc == 0 ? hNew - 1 : hNew, nc, body));
            lastEnd = end;
            // advance numbering to `end`
            for (int k = i; k < end; k++) {
                Edit e = script.get(k);
                if (!(e instanceof Edit.Insert)) {
                    oldNo++;
                }
                if (!(e instanceof Edit.Delete)) {
                    newNo++;
                }
            }
            i = end;
        }
        return out;
    }

    /** Text of a patch (labels, then each hunk). Empty hunk list prints just the two header lines. */
    public static String render(Patch p) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(p.oldLabel()).append('\n');
        sb.append("+++ ").append(p.newLabel()).append('\n');
        for (Hunk h : p.hunks()) {
            sb.append("@@ -").append(range(h.oldStart(), h.oldCount()))
                .append(" +").append(range(h.newStart(), h.newCount())).append(" @@\n");
            for (String l : h.lines()) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    private static String range(int start, int count) {
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }

    /** Parses a unified diff; throws {@link PatchFormatException} naming the first bad line. */
    public static Patch parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.isEmpty() && !text.endsWith("\n")) {
            throw new PatchFormatException("missing newline at end of patch text");
        }
        String[] lines = text.isEmpty() ? new String[0] : text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length < 2 || !lines[0].startsWith("--- ")) {
            throw new PatchFormatException("line 1: expected '--- <label>'");
        }
        if (!lines[1].startsWith("+++ ")) {
            throw new PatchFormatException("line 2: expected '+++ <label>'");
        }
        List<Hunk> hunks = new ArrayList<>();
        int i = 2;
        int prevOldEnd = 0; // 0-based exclusive end of previous hunk in the old text
        while (i < lines.length) {
            int lineNo = i + 1;
            Matcher m = HEADER.matcher(lines[i]);
            if (!m.matches()) {
                throw new PatchFormatException("line " + lineNo + ": expected hunk header '@@ -a,b +c,d @@'");
            }
            int oldStart = Integer.parseInt(m.group(1));
            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            int oldSeen = 0;
            int newSeen = 0;
            List<String> body = new ArrayList<>();
            i++;
            while (oldSeen < oldCount || newSeen < newCount) {
                if (i >= lines.length) {
                    throw new PatchFormatException("line " + lineNo + ": hunk body shorter than header counts");
                }
                String l = lines[i];
                if (l.startsWith("\\")) {
                    throw new PatchFormatException("line " + (i + 1) + ": unsupported '\\' marker");
                }
                char c = l.isEmpty() ? '?' : l.charAt(0);
                if (c == ' ') {
                    oldSeen++;
                    newSeen++;
                } else if (c == '-') {
                    oldSeen++;
                } else if (c == '+') {
                    newSeen++;
                } else {
                    throw new PatchFormatException("line " + (i + 1) + ": body line must start with ' ', '-' or '+'");
                }
                if (oldSeen > oldCount || newSeen > newCount) {
                    throw new PatchFormatException("line " + lineNo + ": hunk body longer than header counts");
                }
                body.add(l);
                i++;
            }
            if (i < lines.length && lines[i].startsWith("\\")) {
                throw new PatchFormatException("line " + (i + 1) + ": unsupported '\\' marker");
            }
            if (i < lines.length && !lines[i].isEmpty() && "+- ".indexOf(lines[i].charAt(0)) >= 0
                && !lines[i].startsWith("--- ") && !lines[i].startsWith("+++ ")) {
                throw new PatchFormatException("line " + (i + 1) + ": extra body line beyond header counts");
            }
            Hunk h = new Hunk(oldStart, oldCount, newStart, newCount, body);
            if (h.oldIndex() < prevOldEnd) {
                throw new PatchFormatException("line " + lineNo + ": hunk out of order or overlapping");
            }
            prevOldEnd = h.oldIndex() + oldCount;
            hunks.add(h);
        }
        return new Patch(lines[0].substring(4), lines[1].substring(4), hunks);
    }
}
