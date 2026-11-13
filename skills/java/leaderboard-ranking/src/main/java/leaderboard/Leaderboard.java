package leaderboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A score table with deterministic ranking (competition ranking: 1,2,2,4). */
public final class Leaderboard {

    /** One ranked row. */
    public record Entry(String player, int total, int rank) { }

    private static final Comparator<Map.Entry<String, Integer>> ORDER =
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Map.Entry::getKey);

    private final Map<String, Integer> totals = new HashMap<>();

    /** Adds points (may be negative) to a player's total. */
    public void record(String player, int points) {
        if (player == null || player.isBlank()) {
            throw new IllegalArgumentException("player must not be blank");
        }
        int updated = Math.addExact(totals.getOrDefault(player, 0), points);
        totals.put(player, updated);
    }

    /** Returns the player's total, or 0 if unknown. */
    public int score(String player) {
        return totals.getOrDefault(player, 0);
    }

    /** All players, best first; tied totals share a rank. */
    public List<Entry> ranking() {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(ORDER);
        List<Entry> out = new ArrayList<>(sorted.size());
        int rank = 0;
        Integer previous = null;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            if (previous == null || e.getValue().intValue() != previous) {
                rank = i + 1;
                previous = e.getValue();
            }
            out.add(new Entry(e.getKey(), e.getValue(), rank));
        }
        return Collections.unmodifiableList(out);
    }

    /** The first {@code n} ranking entries. */
    public List<Entry> top(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        List<Entry> all = ranking();
        return all.subList(0, Math.min(n, all.size()));
    }

    /** Totals (highest first) mapped to the sorted names holding that total. */
    public Map<Integer, List<String>> byScore() {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (Entry e : ranking()) {
            out.computeIfAbsent(e.total(), k -> new ArrayList<>()).add(e.player());
        }
        out.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(out);
    }
}
