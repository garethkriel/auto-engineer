package bench.wordfreq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Top-k word frequency. */
public final class WordFrequency {
    private WordFrequency() {
    }

    public static List<Map.Entry<String, Integer>> top(String text, int k) {
        if (text == null || k < 0) {
            throw new IllegalArgumentException("text must be non-null and k >= 0");
        }
        if (k == 0) {
            return new ArrayList<>();
        }
        Map<String, Integer> counts = new HashMap<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                int start = i;
                while (i < n) {
                    int c = text.codePointAt(i);
                    if (!Character.isLetterOrDigit(c)) {
                        break;
                    }
                    i += Character.charCount(c);
                }
                counts.merge(text.substring(start, i).toLowerCase(Locale.ROOT), 1, Integer::sum);
            } else {
                i += Character.charCount(cp);
            }
        }
        List<Map.Entry<String, Integer>> all = new ArrayList<>(counts.entrySet().size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            all.add(Map.entry(e.getKey(), e.getValue()));
        }
        all.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }
}
