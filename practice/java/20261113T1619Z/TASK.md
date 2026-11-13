# Task: WordStats (Level 3, Map + Comparator sorting)

Implement `practice.WordStats` with static methods:
- `Map<String,Integer> count(String text)`: lower-cased words (letters/apostrophes; split on anything else), counts. Null -> NPE.
- `List<Map.Entry<String,Integer>> top(Map<String,Integer> counts, int n)`: top n by count descending, ties alphabetical ascending. n<0 -> IllegalArgumentException; n larger than size returns all.
- `Map<Integer,List<String>> groupByCount(Map<String,Integer> counts)`: TreeMap keyed by count descending; each list sorted alphabetically.
- `String longestWord(String text)`: longest word (first occurrence wins ties), "" if none.

Success: `mvn -q -B test` passes. Assumption: apostrophes are kept inside words ("don't").
