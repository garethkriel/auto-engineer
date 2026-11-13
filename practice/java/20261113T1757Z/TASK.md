# Task (level 3, topic: List/Map iteration, sorting with Comparator)

Implement `leaderboard.Leaderboard`, a score table.

- `record(String player, int points)`: adds points to the player's total (negative allowed). Null/blank player -> `IllegalArgumentException`.
- `int score(String player)`: total, 0 for unknown players.
- `List<Entry> ranking()`: all players sorted by total descending, ties by name ascending (case-insensitive, then case-sensitive for determinism). `Entry` is a record `(String player, int total, int rank)`; rank uses competition ranking (1,2,2,4): tied totals share a rank.
- `List<Entry> top(int n)`: first n entries of ranking (n<0 -> IAE; n larger than size -> all).
- `Map<Integer, List<String>> byScore()`: totals -> player names (sorted), iterating highest score first (LinkedHashMap).
- Returned collections are unmodifiable.

Success: `mvn -q -B test` passes in this folder; tests written first.
Assumption: totals use `long`-free int arithmetic with `Math.addExact` (overflow -> ArithmeticException).
