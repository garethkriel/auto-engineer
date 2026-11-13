# leaderboard-ranking

- **Description:** Score table with deterministic ranking: composed `Comparator` (total desc, name case-insensitive, then exact name), competition ranks (1,2,2,4), `top(n)`, and a `LinkedHashMap` grouping by score. Returns unmodifiable views; `Math.addExact` guards overflow.
- **Language:** java
- **Tags:** collections, Comparator, sorting, Map, ranking, unmodifiable
- **Source round:** practice/java/20261113T1757Z
