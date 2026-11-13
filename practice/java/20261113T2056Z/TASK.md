# Task: InvertedIndex (level 3, Set/Map/StringBuilder/Comparator)

Implement `ae.InvertedIndex`, a tiny full-text index.

- `add(int docId, String text)`: tokenises `text` (lower-case via `Locale.ROOT`, tokens are maximal runs of letters/digits) and records which docs contain each term. Re-adding a docId adds to it (does not replace). Null text -> NPE; negative docId -> IllegalArgumentException.
- `search(String term)`: sorted set of doc ids containing the term (case-insensitive); empty set if unknown. Result is unmodifiable.
- `all(String... terms)`: docs containing every term (intersection); no terms -> empty set.
- `any(String... terms)`: docs containing at least one term (union).
- `topTerms(int n)`: up to n terms ordered by total occurrence count descending, then alphabetically. n < 0 -> IAE.
- `render()`: one line per term, alphabetical: `term -> [1, 3]` joined by `\n`, built with StringBuilder; empty index -> "".

Assumption: occurrence counts count every token occurrence across all docs.
Success: `mvn -q -B test` passes.
