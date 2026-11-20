package ae.suffix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * FM-index: backward search over the BWT with occ checkpoints and a suffix array sampled by
 * text position (every {@code saStep}-th position), so {@code locate} walks LF at most saStep-1 steps.
 */
public final class FmIndex {
    private static final int BLOCK = 16;

    private final char[] bwt;
    private final char[] alphabet; // sorted distinct chars of bwt (includes the sentinel)
    private final int[] c; // c[k] = number of bwt chars smaller than alphabet[k]
    private final int[][] checkpoints; // checkpoints[b][k] = occurrences of alphabet[k] in bwt[0 .. b*BLOCK)
    private final long[] marked; // bit per row: this row's suffix start is sampled
    private final int[] markedBefore; // popcount of marked words before each word
    private final int[] samples; // suffix start for each marked row, in row order

    public FmIndex(String text, int saStep) {
        Objects.requireNonNull(text, "text");
        if (saStep < 1) throw new IllegalArgumentException("saStep must be >= 1");
        if (text.indexOf(Bwt.SENTINEL) >= 0) throw new IllegalArgumentException("text contains NUL");
        String t = text + Bwt.SENTINEL;
        int m = t.length();
        int[] sa = SuffixArray.build(t.chars().toArray());
        bwt = new char[m];
        for (int i = 0; i < m; i++) bwt[i] = t.charAt(sa[i] == 0 ? m - 1 : sa[i] - 1);
        alphabet = distinctSorted(bwt);
        int sigma = alphabet.length;
        int[] total = new int[sigma];
        for (char ch : bwt) total[symbol(ch)]++;
        c = new int[sigma];
        for (int k = 1; k < sigma; k++) c[k] = c[k - 1] + total[k - 1];
        int blocks = m / BLOCK + 1;
        checkpoints = new int[blocks][sigma];
        int[] running = new int[sigma];
        for (int i = 0; i < m; i++) {
            if (i % BLOCK == 0) checkpoints[i / BLOCK] = running.clone();
            running[symbol(bwt[i])]++;
        }
        if (m % BLOCK == 0) checkpoints[m / BLOCK] = running.clone();
        marked = new long[(m + 63) / 64];
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (sa[i] % saStep == 0) {
                marked[i >> 6] |= 1L << (i & 63);
                kept.add(sa[i]);
            }
        }
        samples = kept.stream().mapToInt(Integer::intValue).toArray();
        markedBefore = new int[marked.length];
        for (int w = 1; w < marked.length; w++) {
            markedBefore[w] = markedBefore[w - 1] + Long.bitCount(marked[w - 1]);
        }
    }

    private static char[] distinctSorted(char[] chars) {
        char[] copy = chars.clone();
        Arrays.sort(copy);
        int n = 0;
        for (int i = 0; i < copy.length; i++) {
            if (i == 0 || copy[i] != copy[i - 1]) copy[n++] = copy[i];
        }
        return Arrays.copyOf(copy, n);
    }

    /** Index of {@code ch} in the alphabet, or -1. */
    private int symbol(char ch) {
        return Arrays.binarySearch(alphabet, ch);
    }

    /** Occurrences of alphabet symbol k in bwt[0, i). */
    private int occ(int k, int i) {
        int b = i / BLOCK;
        int n = checkpoints[b][k];
        for (int j = b * BLOCK; j < i; j++) {
            if (bwt[j] == alphabet[k]) n++;
        }
        return n;
    }

    private boolean isMarked(int row) {
        return (marked[row >> 6] >>> (row & 63) & 1L) != 0;
    }

    private int sampleAt(int row) {
        int w = row >> 6;
        long below = marked[w] & ((1L << (row & 63)) - 1);
        return samples[markedBefore[w] + Long.bitCount(below)];
    }

    /** Half-open row range of suffixes prefixed by {@code p}; empty range when absent. */
    private int[] search(String p) {
        Objects.requireNonNull(p, "pattern");
        if (p.isEmpty()) throw new IllegalArgumentException("empty pattern");
        int lo = 0;
        int hi = bwt.length;
        for (int i = p.length() - 1; i >= 0; i--) {
            char ch = p.charAt(i);
            int k = symbol(ch);
            if (ch == Bwt.SENTINEL || k < 0) return new int[] {0, 0};
            lo = c[k] + occ(k, lo);
            hi = c[k] + occ(k, hi);
            if (lo >= hi) return new int[] {0, 0};
        }
        return new int[] {lo, hi};
    }

    public int count(String pattern) {
        int[] r = search(pattern);
        return r[1] - r[0];
    }

    /** Start positions of all occurrences, ascending. */
    public List<Integer> locate(String pattern) {
        int[] r = search(pattern);
        List<Integer> out = new ArrayList<>(r[1] - r[0]);
        for (int row = r[0]; row < r[1]; row++) {
            int steps = 0;
            int cur = row;
            while (!isMarked(cur)) {
                int k = symbol(bwt[cur]);
                cur = c[k] + occ(k, cur);
                steps++;
            }
            out.add(sampleAt(cur) + steps);
        }
        out.sort(null);
        return out;
    }
}
