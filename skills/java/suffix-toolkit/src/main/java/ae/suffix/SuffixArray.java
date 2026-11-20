package ae.suffix;

import java.util.Arrays;
import java.util.Objects;

/** Suffix array construction (prefix doubling, O(n log n)) and Kasai LCP. */
public final class SuffixArray {
    private SuffixArray() {}

    /** Returns the start indexes of all suffixes of {@code text} in ascending order. */
    public static int[] build(int[] text) {
        Objects.requireNonNull(text, "text");
        int n = text.length;
        int[] sa = new int[n];
        if (n == 0) return sa;
        // dense initial ranks: index of the element in the sorted distinct values
        int[] distinct = Arrays.stream(text).sorted().distinct().toArray();
        int classes = distinct.length;
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) rank[i] = Arrays.binarySearch(distinct, text[i]);
        int[] counts = new int[classes + 1];
        for (int i = 0; i < n; i++) counts[rank[i]]++;
        for (int i = 1; i <= classes; i++) counts[i] += counts[i - 1];
        for (int i = n - 1; i >= 0; i--) sa[--counts[rank[i]]] = i;

        int[] second = new int[n];
        int[] next = new int[n];
        for (int k = 1; classes < n; k <<= 1) {
            // order by second key: suffixes with no second half first, then by rank of i+k
            int p = 0;
            for (int i = n - k; i < n; i++) second[p++] = i;
            for (int j = 0; j < n; j++) {
                if (sa[j] >= k) second[p++] = sa[j] - k;
            }
            int[] cnt = new int[classes + 1];
            for (int i = 0; i < n; i++) cnt[rank[i]]++;
            for (int i = 1; i <= classes; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) sa[--cnt[rank[second[i]]]] = second[i];
            next[sa[0]] = 0;
            int c = 1;
            for (int i = 1; i < n; i++) {
                int a = sa[i - 1];
                int b = sa[i];
                boolean same = rank[a] == rank[b] && secondKey(rank, a, k, n) == secondKey(rank, b, k, n);
                if (!same) c++;
                next[b] = c - 1;
            }
            int[] tmp = rank;
            rank = next;
            next = tmp;
            classes = c;
        }
        return sa;
    }

    private static int secondKey(int[] rank, int i, int k, int n) {
        if (i + k < n) return rank[i + k];
        return -1;
    }

    /**
     * Kasai's algorithm. {@code lcp[i]} is the longest common prefix of the suffixes
     * {@code sa[i-1]} and {@code sa[i]}; {@code lcp[0]} is 0.
     */
    public static int[] lcp(int[] text, int[] sa) {
        int n = text.length;
        int[] lcp = new int[n];
        int[] inv = new int[n];
        for (int i = 0; i < n; i++) inv[sa[i]] = i;
        int h = 0;
        for (int i = 0; i < n; i++) {
            if (inv[i] > 0) {
                int j = sa[inv[i] - 1];
                while (i + h < n && j + h < n && text[i + h] == text[j + h]) h++;
                lcp[inv[i]] = h;
                if (h > 0) h--;
            } else {
                h = 0;
            }
        }
        return lcp;
    }
}
