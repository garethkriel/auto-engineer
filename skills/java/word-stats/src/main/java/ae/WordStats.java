package ae;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Word-frequency helpers built on collections and {@link Comparator}. */
public final class WordStats {
    private static final Comparator<WordCount> BY_COUNT_THEN_WORD =
            Comparator.comparingInt(WordCount::count).reversed().thenComparing(WordCount::word);

    private WordStats() {
    }

    /** Lower-case words (runs of letters or apostrophes) in order of appearance. */
    public static List<String> tokenize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || c == '\'') {
                current.append(c);
            } else if (current.length() > 0) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    /** The {@code n} most frequent words, by count descending then word ascending. */
    public static List<WordCount> topWords(String text, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String w : tokenize(text)) {
            counts.merge(w, 1, Integer::sum);
        }
        List<WordCount> all = new ArrayList<>();
        counts.forEach((w, c) -> all.add(new WordCount(w, c)));
        all.sort(BY_COUNT_THEN_WORD);
        return List.copyOf(all.subList(0, Math.min(n, all.size())));
    }

    /** Distinct words grouped by length; keys and words are sorted. */
    public static SortedMap<Integer, SortedSet<String>> groupByLength(String text) {
        SortedMap<Integer, SortedSet<String>> groups = new TreeMap<>();
        for (String w : tokenize(text)) {
            groups.computeIfAbsent(w.length(), k -> new TreeSet<>()).add(w);
        }
        return groups;
    }
}
