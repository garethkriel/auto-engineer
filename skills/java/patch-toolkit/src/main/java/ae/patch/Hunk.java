package ae.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * One unified-diff hunk. {@code oldStart}/{@code newStart} are the raw header numbers (1-based;
 * for an empty side the number of the line before the position, so 0 means "top of file").
 * Each body line starts with ' ' (context), '-' (delete) or '+' (insert).
 */
public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {
    public Hunk {
        lines = List.copyOf(lines);
    }

    /** 0-based index in the old text where {@link #oldLines()} begins. */
    public int oldIndex() {
        return oldCount == 0 ? oldStart : oldStart - 1;
    }

    /** Lines the old text must contain here (context and deletes, prefix stripped). */
    public List<String> oldLines() {
        return side('+');
    }

    /** Lines the new text has here (context and inserts, prefix stripped). */
    public List<String> newLines() {
        return side('-');
    }

    private List<String> side(char skip) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.charAt(0) != skip) {
                out.add(l.substring(1));
            }
        }
        return out;
    }

    /** The hunk that undoes this one. */
    Hunk reverse() {
        List<String> flipped = new ArrayList<>();
        for (String l : lines) {
            char c = l.charAt(0);
            flipped.add((c == '-' ? "+" : c == '+' ? "-" : " ") + l.substring(1));
        }
        return new Hunk(newStart, newCount, oldStart, oldCount, flipped);
    }
}
