package bench.wordfreq;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Top-k word frequency. */
public final class WordFrequency {
    private WordFrequency() {}

    public static List<Map.Entry<String, Integer>> top(String text, int k) {
        if (text == null || k < 0) {
            throw new IllegalArgumentException("bad arguments");
        }
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else if (word.length() > 0) {
                counts.merge(word.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                word.setLength(0);
            }
        }
        if (word.length() > 0) {
            counts.merge(word.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> all = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            all.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        all.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }
}
