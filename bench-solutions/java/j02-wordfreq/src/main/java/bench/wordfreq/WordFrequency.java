package bench.wordfreq;

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
            throw new IllegalArgumentException("bad argument");
        }
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                cur.appendCodePoint(cp);
            } else {
                flush(cur, counts);
            }
        }
        flush(cur, counts);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (int j = 0; j < Math.min(k, list.size()); j++) {
            out.add(Map.entry(list.get(j).getKey(), list.get(j).getValue()));
        }
        return out;
    }

    private static void flush(StringBuilder cur, Map<String, Integer> counts) {
        if (cur.length() > 0) {
            counts.merge(cur.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
            cur.setLength(0);
        }
    }
}
