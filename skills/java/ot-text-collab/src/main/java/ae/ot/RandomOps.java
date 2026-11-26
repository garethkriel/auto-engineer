package ae.ot;

import java.util.Objects;
import java.util.Random;

/** Random text and random operations, used by the simulation and by tests. */
public final class RandomOps {
    private RandomOps() { }

    /** Random text of {@code length} chars drawn from {@code alphabet}. */
    public static String randomText(Random rng, int length, String alphabet) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        return sb.toString();
    }

    /**
     * A random operation applicable to {@code doc}: mostly retains, with occasional inserts (1-3 chars
     * from {@code alphabet}) and deletes. Never a no-op.
     */
    public static TextOp generate(Random rng, String doc, String alphabet) {
        Objects.requireNonNull(doc);
        int len = doc.length();
        TextOp.Builder b = TextOp.builder();
        int pos = 0;
        while (pos < len) {
            int r = rng.nextInt(8);
            if (r == 0) {
                b.insert(randomText(rng, 1 + rng.nextInt(3), alphabet));
            } else if (r == 1) {
                int n = 1 + rng.nextInt(Math.min(3, len - pos));
                b.delete(n);
                pos += n;
            } else {
                int n = 1 + rng.nextInt(Math.min(4, len - pos));
                b.retain(n);
                pos += n;
            }
        }
        if (rng.nextInt(4) == 0) b.insert(randomText(rng, 1 + rng.nextInt(3), alphabet));
        TextOp op = b.build();
        if (op.isNoop()) {
            int at = rng.nextInt(len + 1);
            return TextOp.builder().retain(at).insert(randomText(rng, 1 + rng.nextInt(3), alphabet))
                    .retain(len - at).build();
        }
        return op;
    }
}
