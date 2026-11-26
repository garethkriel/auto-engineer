package ae.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies a {@link Patch} to a list of lines with exact context matching (fuzz 0) and offset search. */
public final class Patcher {
    private Patcher() { }

    /**
     * Outcome of {@link #apply}. {@code offsets} has one entry per hunk: the shift from the header
     * position at which it applied, or {@code null} when rejected; {@code rejected} lists those indices.
     */
    public record Result(List<String> lines, List<Integer> offsets, List<Integer> rejected) {
        public boolean ok() {
            return rejected.isEmpty();
        }
    }

    public static Result apply(List<String> src, Patch patch, int maxOffset) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(patch, "patch");
        if (maxOffset < 0) {
            throw new IllegalArgumentException("maxOffset must be >= 0");
        }
        List<String> out = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> rejected = new ArrayList<>();
        int copied = 0; // src[0..copied) already emitted
        int carry = 0;
        for (int hi = 0; hi < patch.hunks().size(); hi++) {
            Hunk h = patch.hunks().get(hi);
            List<String> old = h.oldLines();
            int expected = h.oldIndex();
            int found = -1;
            for (int k = 0; k <= 2 * maxOffset + 1 && found < 0; k++) {
                // 0, +1, -1, +2, -2 ... relative to expected + carry
                int step = k == 0 ? 0 : (k % 2 == 1 ? (k + 1) / 2 : -(k / 2));
                if (Math.abs(step) > maxOffset) {
                    continue;
                }
                int pos = expected + carry + step;
                if (pos >= copied && pos + old.size() <= src.size() && matches(src, pos, old)) {
                    found = pos;
                }
            }
            if (found < 0) {
                offsets.add(null);
                rejected.add(hi);
                continue;
            }
            out.addAll(src.subList(copied, found));
            out.addAll(h.newLines());
            copied = found + old.size();
            carry = found - expected;
            offsets.add(carry);
        }
        out.addAll(src.subList(copied, src.size()));
        return new Result(List.copyOf(out), offsets, rejected);
    }

    private static boolean matches(List<String> src, int pos, List<String> old) {
        for (int i = 0; i < old.size(); i++) {
            if (!src.get(pos + i).equals(old.get(i))) {
                return false;
            }
        }
        return true;
    }
}
