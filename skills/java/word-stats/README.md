# word-stats
- **Description:** Word tokenizing, top-N frequency ranking (count desc, word asc via chained `Comparator`), and grouping into `SortedMap<Integer, SortedSet<String>>`.
- **Language:** java
- **Tags:** collections, comparator, hashmap-merge, treemap, sorting, record
- **Source round:** practice/java/20261113T1656Z
- **Reuse:** `Comparator.comparingInt(..).reversed().thenComparing(..)` for deterministic ranking; never rely on HashMap order.
