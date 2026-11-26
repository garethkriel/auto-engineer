package bench.wordfreq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WordFrequency {
    private WordFrequency() {}

    public static List<Map.Entry<String, Integer>> top(String text, int k) {
        if (text == null || k < 0) throw new IllegalArgumentException();
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            } else if (sb.length() > 0) {
                counts.merge(sb.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) counts.merge(sb.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) list.add(Map.entry(e.getKey(), e.getValue()));
        list.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return new ArrayList<>(list.subList(0, Math.min(k, list.size())));
    }
}
