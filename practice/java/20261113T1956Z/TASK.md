# Task: WordTools (level 3, Set/Map/StringBuilder/Comparator)

Package `ae`, class `WordTools` (final, static methods):
- `List<String> topWords(String text, int n)`: words = maximal runs of ASCII letters/apostrophes-free letters `[A-Za-z]`, lower-cased with `Locale.ROOT`. Return up to n words ordered by count desc, then alphabetically. `n<0` -> IllegalArgumentException; null text -> NPE.
- `List<List<String>> anagramGroups(List<String> words)`: group words (case-insensitive, compared lower-cased) with the same sorted letters; only groups with >=2 distinct lower-cased words; each group sorted, duplicates removed; groups sorted by first element.
- `String wrap(String text, int width)`: greedy word wrap on whitespace using StringBuilder; lines joined by "\n", no trailing newline; a word longer than width gets its own line unbroken; width<1 -> IllegalArgumentException; blank text -> "".
- `SortedSet<String> commonWords(String a, String b)`: distinct lower-cased words present in both texts.

Assumptions: word = `[A-Za-z]+`. Success: `mvn -q -B test` passes.
