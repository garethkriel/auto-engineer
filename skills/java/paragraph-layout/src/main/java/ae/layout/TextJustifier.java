package ae.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Monospaced paragraph justification on top of {@link Breaker}. */
public final class TextJustifier {
    private static final int FILL = 100_000;
    private static final int HYPHEN_PENALTY = 50;
    private static final long[] TOLERANCES = {100, 400, Params.MAX_TOLERANCE};

    private TextJustifier() {}

    /**
     * Justifies {@code text} to {@code width} columns.
     *
     * @param hyphenator returns ascending cut positions strictly inside the word (may be empty)
     * @return the lines; every line but the last is exactly {@code width} wide
     * @throws LayoutException if some word cannot be placed (e.g. longer than the width, unhyphenated)
     */
    public static List<String> justify(String text, int width, Function<String, int[]> hyphenator)
            throws LayoutException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(hyphenator, "hyphenator");
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        boolean first = true;
        for (String word : trimmed.split("\\s+")) {
            if (!first) {
                items.add(new Item.Glue(1, 1, 0));
            }
            first = false;
            int[] cuts = Objects.requireNonNull(hyphenator.apply(word), "hyphenator result");
            int prev = 0;
            for (int cut : cuts) {
                if (cut <= prev || cut >= word.length()) {
                    throw new IllegalArgumentException("invalid hyphen position " + cut + " in '" + word + "'");
                }
                items.add(new Item.Box(cut - prev, word.substring(prev, cut)));
                items.add(new Item.Penalty(1, HYPHEN_PENALTY, true));
                prev = cut;
            }
            items.add(new Item.Box(word.length() - prev, word.substring(prev)));
        }
        items.add(new Item.Penalty(0, Item.PENALTY_INF, false));
        items.add(new Item.Glue(0, FILL, 0));
        items.add(new Item.Penalty(0, -Item.PENALTY_INF, false));

        LayoutException last = null;
        for (long tolerance : TOLERANCES) {
            try {
                Layout layout = Breaker.breakLines(items, width, Params.DEFAULT.withTolerance(tolerance));
                return render(items, layout);
            } catch (LayoutException e) {
                last = e;
            }
        }
        throw last;
    }

    private static List<String> render(List<Item> items, Layout layout) {
        List<String> out = new ArrayList<>();
        int count = layout.lines().size();
        for (int k = 0; k < count; k++) {
            Layout.Line line = layout.lines().get(k);
            boolean isLast = k == count - 1;
            int lastBox = line.to() - 1;
            while (lastBox >= line.from() && !(items.get(lastBox) instanceof Item.Box)) {
                lastBox--;
            }
            int glues = 0;
            for (int j = line.from(); j <= lastBox; j++) {
                if (items.get(j) instanceof Item.Glue) {
                    glues++;
                }
            }
            long extra = isLast ? 0 : line.targetWidth() - line.naturalWidth();
            StringBuilder sb = new StringBuilder();
            int glue = 0;
            for (int j = line.from(); j <= lastBox; j++) {
                Item it = items.get(j);
                if (it instanceof Item.Box b) {
                    sb.append(b.text());
                } else if (it instanceof Item.Glue) {
                    long spaces = 1 + extra / glues + (glue < extra % glues ? 1 : 0);
                    sb.append(" ".repeat((int) spaces));
                    glue++;
                }
            }
            if (items.get(line.to()) instanceof Item.Penalty p && p.flagged()) {
                sb.append('-');
            }
            out.add(sb.toString());
        }
        return out;
    }
}
