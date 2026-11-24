package ae.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A parsed unified diff: file names plus ordered hunks. */
public record Patch(String oldName, String newName, List<Hunk> hunks) {

    public Patch {
        Objects.requireNonNull(oldName);
        Objects.requireNonNull(newName);
        hunks = List.copyOf(hunks);
    }

    /** One body line: tag is {@code ' '} (context), {@code '-'} (delete) or {@code '+'} (insert). */
    public record Line(char tag, String text) {
        public Line {
            if (tag != ' ' && tag != '-' && tag != '+') {
                throw new IllegalArgumentException("bad tag " + tag);
            }
            Objects.requireNonNull(text);
        }
    }

    /** A hunk with 1-based starts; an empty range starts at the line before (0 at the top). */
    public record Hunk(int oldStart, int oldLen, int newStart, int newLen, List<Line> lines) {
        public Hunk {
            lines = List.copyOf(lines);
        }
    }

    /** Applies the patch strictly: all context and deleted lines must match exactly. */
    public List<String> apply(List<String> input) {
        List<String> out = new ArrayList<>();
        int cursor = 0;
        for (int h = 0; h < hunks.size(); h++) {
            Hunk hunk = hunks.get(h);
            String where = "hunk " + (h + 1);
            int oldCount = 0;
            int newCount = 0;
            for (Line l : hunk.lines()) {
                if (l.tag() != '+') {
                    oldCount++;
                }
                if (l.tag() != '-') {
                    newCount++;
                }
            }
            if (oldCount != hunk.oldLen() || newCount != hunk.newLen()) {
                throw new PatchException(where + ": line counts disagree with header");
            }
            int start = hunk.oldLen() == 0 ? hunk.oldStart() : hunk.oldStart() - 1;
            if (start < cursor) {
                throw new PatchException(where + ": overlaps or precedes the previous hunk");
            }
            if (start > input.size()) {
                throw new PatchException(where + ": starts beyond the end of the input");
            }
            out.addAll(input.subList(cursor, start));
            int expectedNew = hunk.newLen() == 0 ? hunk.newStart() : hunk.newStart() - 1;
            if (out.size() != expectedNew) {
                throw new PatchException(where + ": new start " + hunk.newStart()
                        + " does not match output position " + out.size());
            }
            int pos = start;
            for (Line l : hunk.lines()) {
                if (l.tag() == '+') {
                    out.add(l.text());
                    continue;
                }
                if (pos >= input.size() || !input.get(pos).equals(l.text())) {
                    throw new PatchException(where + ": mismatch at input line " + (pos + 1)
                            + ", expected \"" + l.text() + "\"");
                }
                if (l.tag() == ' ') {
                    out.add(l.text());
                }
                pos++;
            }
            cursor = pos;
        }
        out.addAll(input.subList(cursor, input.size()));
        return out;
    }

    /** The patch that undoes this one. */
    public Patch reverse() {
        List<Hunk> rev = new ArrayList<>();
        for (Hunk h : hunks) {
            List<Line> ls = new ArrayList<>();
            for (Line l : h.lines()) {
                char t = l.tag() == '-' ? '+' : l.tag() == '+' ? '-' : ' ';
                ls.add(new Line(t, l.text()));
            }
            rev.add(new Hunk(h.newStart(), h.newLen(), h.oldStart(), h.oldLen(), ls));
        }
        return new Patch(newName, oldName, rev);
    }
}
