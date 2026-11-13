package practice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.HashMap;

/** Word frequency helpers built on Map and Comparator. */
public final class WordStats {
    private WordStats() {}

    private static List<String> words(String text) {
        Objects.requireNonNull(text, "text");
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase().split("[^\\p{L}']+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Counts lower-cased words. */
    public static Map<String, Integer> count(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String w : words(text)) {
            m.merge(w, 1, Integer::sum);
        }
        return m;
    }

    /** Top n entries by count descending, ties alphabetical. */
    public static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int n) {
        Objects.requireNonNull(counts, "counts");
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return List.copyOf(list.subList(0, Math.min(n, list.size())));
    }

    /** Groups words by count; keys descending, words sorted. */
    public static Map<Integer, List<String>> groupByCount(Map<String, Integer> counts) {
        Map<Integer, List<String>> g = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            g.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        g.values().forEach(Collections::sort);
        return g;
    }

    /** Longest word; first occurrence wins ties; "" if none. */
    public static String longestWord(String text) {
        String best = "";
        for (String w : words(text)) {
            if (w.length() > best.length()) {
                best = w;
            }
        }
        return best;
    }
}
