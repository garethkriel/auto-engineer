package cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Factory for the built-in edit commands. Commands are stateless and reusable. */
public final class Commands {
    private Commands() {}

    private static void checkRange(StringBuilder doc, int pos, int length) {
        if (pos < 0 || length < 0 || pos > doc.length() || length > doc.length() - pos) {
            throw new IllegalArgumentException(
                    "range [" + pos + ", +" + length + ") outside document of length " + doc.length());
        }
    }

    /** Inserts {@code text} at {@code pos} (0..length inclusive). */
    public static Command insert(int pos, String text) {
        Objects.requireNonNull(text, "text");
        return doc -> {
            checkRange(doc, pos, 0);
            doc.insert(pos, text);
            return delete(pos, text.length());
        };
    }

    /** Deletes {@code length} chars starting at {@code pos}. */
    public static Command delete(int pos, int length) {
        return doc -> {
            checkRange(doc, pos, length);
            String removed = doc.substring(pos, pos + length);
            doc.delete(pos, pos + length);
            return insert(pos, removed);
        };
    }

    /** Replaces {@code length} chars at {@code pos} with {@code text}. */
    public static Command replace(int pos, int length, String text) {
        Objects.requireNonNull(text, "text");
        return doc -> {
            checkRange(doc, pos, length);
            String removed = doc.substring(pos, pos + length);
            doc.replace(pos, pos + length, text);
            return replace(pos, text.length(), removed);
        };
    }

    /** Runs the steps in order; atomic: on failure earlier steps are rolled back. */
    public static Command macro(List<Command> steps) {
        List<Command> copy = List.copyOf(steps);
        return doc -> {
            List<Command> inverses = new ArrayList<>();
            try {
                for (Command c : copy) {
                    inverses.add(c.apply(doc));
                }
            } catch (RuntimeException ex) {
                for (int i = inverses.size() - 1; i >= 0; i--) {
                    inverses.get(i).apply(doc);
                }
                throw ex;
            }
            List<Command> reversed = new ArrayList<>(inverses);
            java.util.Collections.reverse(reversed);
            return macro(reversed);
        };
    }
}
