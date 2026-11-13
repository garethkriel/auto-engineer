package ae;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text utilities built on collections, Comparator and StringBuilder. */
public final class WordTools {
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    private WordTools() {
    }

    private static List<String> words(String text) {
        Objects.requireNonNull(text, "text");
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Up to n most frequent words, by count descending then alphabetically. */
    public static List<String> topWords(String text, int n) {
        Objects.requireNonNull(text, "text");
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String w : words(text)) {
            counts.merge(w, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, entries.size()); i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }

    /** Groups of at least two distinct anagrams, each sorted, groups sorted by first element. */
    public static List<List<String>> anagramGroups(List<String> words) {
        Objects.requireNonNull(words, "words");
        Map<String, TreeSet<String>> byKey = new TreeMap<>();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT);
            char[] chars = lower.toCharArray();
            Arrays.sort(chars);
            byKey.computeIfAbsent(new String(chars), k -> new TreeSet<>()).add(lower);
        }
        List<List<String>> groups = new ArrayList<>();
        for (TreeSet<String> set : byKey.values()) {
            if (set.size() >= 2) {
                groups.add(List.copyOf(set));
            }
        }
        groups.sort(Comparator.comparing(g -> g.get(0)));
        return groups;
    }

    /** Greedy word wrap at {@code width} columns; over-long words stay whole on their own line. */
    public static String wrap(String text, int width) {
        Objects.requireNonNull(text, "text");
        if (width < 1) {
            throw new IllegalArgumentException("width must be >= 1");
        }
        StringBuilder out = new StringBuilder();
        int lineLen = 0;
        for (String w : text.trim().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (lineLen == 0) {
                out.append(w);
                lineLen = w.length();
            } else if (lineLen + 1 + w.length() <= width) {
                out.append(' ').append(w);
                lineLen += 1 + w.length();
            } else {
                out.append('\n').append(w);
                lineLen = w.length();
            }
        }
        return out.toString();
    }

    /** Distinct lower-cased words present in both texts, sorted. */
    public static SortedSet<String> commonWords(String a, String b) {
        TreeSet<String> result = new TreeSet<>(words(a));
        result.retainAll(new TreeSet<>(words(b)));
        return Collections.unmodifiableSortedSet(result);
    }
}
