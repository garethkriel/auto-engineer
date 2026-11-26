package ae.patch;

import java.util.ArrayList;
import java.util.List;

/** A single-file unified diff: two labels and ascending hunks. */
public record Patch(String oldLabel, String newLabel, List<Hunk> hunks) {
    public Patch {
        hunks = List.copyOf(hunks);
    }

    /** The patch that turns the new text back into the old one. */
    public Patch reverse() {
        List<Hunk> out = new ArrayList<>();
        for (Hunk h : hunks) {
            out.add(h.reverse());
        }
        return new Patch(newLabel, oldLabel, out);
    }
}
