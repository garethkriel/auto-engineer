# Task: WordStats (level 3, collections + Comparator)

Package `ae`, class `WordStats` (final, static methods) and record `WordCount(String word, int count)`.

- `static List<String> tokenize(String text)`: lower-case (Locale.ROOT) words in order. A word is a maximal run of letters or apostrophes `'`; everything else separates. Null text -> `IllegalArgumentException`.
- `static List<WordCount> topWords(String text, int n)`: the `n` most frequent words, sorted by count descending then word ascending. Fewer than n distinct words -> all of them. n < 0 -> `IllegalArgumentException`; n == 0 -> empty list.
- `static SortedMap<Integer, SortedSet<String>> groupByLength(String text)`: distinct words grouped by length, keys ascending, words alphabetical.

Success: `mvn -q -B test` passes. Assumption: leading/trailing apostrophes are kept as part of the word (no trimming).
