# Task 20261124T1756Z — `ae.aho`: Aho-Corasick multi-pattern matcher

Level 20 capstone. Language: Java 21, Maven, JUnit 5 (see template pom).

## Overlap check
Listed `benchmark/java/` (j01-roman .. j35-minisql) first. Nearest: j02-wordfreq, j12-fileindex, j22-regex, j20-cron. None is a multi-pattern automaton or streaming matcher. No existing skill implements Aho-Corasick (grep of skills/java README files). Not a diff/LCS task (rejected PR #136 lesson).

## API (package `ae.aho`)
- `record Match(int start, int end, int pattern)`: half-open `[start,end)` in the text, `pattern` = index in the pattern list.
- `AhoCorasick.build(List<String> patterns, boolean ignoreCase)`: patterns must be non-empty (IllegalArgumentException with message naming the index), list not null; duplicates allowed (each index is reported). `ignoreCase` compares with `Character.toLowerCase(Character.toUpperCase(c))` per char (length preserving, so offsets stay valid).
- `List<Match> findAll(CharSequence text)`: every occurrence (overlaps included), sorted by `(end, start, pattern)`.
- `int[] countPerPattern(CharSequence text)`.
- `List<Match> leftmostLongest(CharSequence text)`: non-overlapping scan from the left; at each position take the longest match starting there (ties between duplicate patterns: lowest index), then continue at its end.
- `String replaceAll(CharSequence text, List<String> replacements)`: applies `leftmostLongest`, replacement i for pattern i; replacements size must equal pattern count.
- `Stream stream()`: stateful matcher; `List<Match> feed(CharSequence chunk)` returns matches ending inside this chunk with absolute offsets (same order as findAll); results are independent of how the input is split into chunks; `long position()`; `reset()`.
- `int patternCount()`, `int nodeCount()`, package-private `checkInvariants()` (fail link depth strictly smaller, fail link of root children is root, output link points to the nearest proper-suffix node that ends a pattern, node depth = parent depth + 1, every pattern end node lists the pattern).

## Success criteria
- `mvn -q -B clean test` passes.
- Literal known answers (classic `he/she/his/hers` over `ushers`), plus randomized differential tests against a brute-force `regionMatches` oracle for findAll, leftmostLongest, replaceAll, countPerPattern, and stream chunking (random splits incl. empty chunks).
- Invariant checker run after build in all randomized runs; corrupting a built automaton makes the checker throw.
- Hand mutation check of the main rules recorded in the lesson.
