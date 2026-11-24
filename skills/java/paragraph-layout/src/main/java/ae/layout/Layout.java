package ae.layout;

import java.util.List;

/**
 * Result of {@link Breaker#breakLines}.
 *
 * @param breaks           indices of the chosen break items, ascending; the last is the final forced break
 * @param lines            one entry per break
 * @param totalDemerits    sum of the line demerits
 * @param peakActiveNodes  largest size of the active set (a measure of how well pruning works)
 */
public record Layout(List<Integer> breaks, List<Line> lines, long totalDemerits, int peakActiveNodes) {
    /** One laid-out line: items {@code from} (first box) up to but excluding {@code to} (the break). */
    public record Line(int from, int to, long naturalWidth, int targetWidth, long stretch, long shrink,
                       long badness, long demerits) {}
}
