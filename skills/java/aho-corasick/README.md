# aho-corasick

- **Description:** Aho-Corasick multi-pattern matcher: trie with BFS failure and output links, `findAll` (overlaps, sorted by end/start/pattern), `countPerPattern`, `leftmostLongest`, `replaceAll`, optional case folding, and a chunk-independent streaming matcher with absolute offsets. Package-private `checkInvariants()` verifies links.
- **Language:** java
- **Tags:** strings, automaton, multi-pattern, streaming, differential-testing, mutation-check
- **Source round:** practice/java/20261124T1756Z

Usage: `AhoCorasick ac = AhoCorasick.build(List.of("he","she","hers"), false); ac.findAll("ushers"); ac.stream().feed(chunk);`
