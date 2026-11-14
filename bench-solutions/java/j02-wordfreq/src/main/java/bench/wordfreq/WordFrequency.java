package bench.wordfreq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Top-k word frequency. */
public final class WordFrequency {
    private WordFrequency() { }

    public static List<Map.Entry<String, Integer>> top(String text, int k) {
        if (text == null || k < 0) {
            throw new IllegalArgumentException("bad argument");
        }
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder w = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                w.appendCodePoint(cp);
            } else if (w.length() > 0) {
                counts.merge(w.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                w.setLength(0);
            }
        }
        if (w.length() > 0) {
            counts.merge(w.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(k)
                .forEach(e -> out.add(Map.entry(e.getKey(), e.getValue())));
        return out;
    }
}
