# Task: path glob matcher (level 9, recursion + memoisation + parsing)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex) - none is glob/wildcard matching.

Implement `glob.Glob`: `Glob.compile(String pattern)` returns a `Glob`; `boolean matches(String path)` matches the WHOLE path.

Syntax:
- `?` one char except `/`
- `*` zero or more chars except `/`
- `**` zero or more chars including `/`
- `**/` zero or more whole directories (so `a/**/b` matches `a/b` and `a/x/y/b`)
- `[abc]`, `[a-z]`, `[!abc]` char class (never matches `/`, even negated); `]` first in the class is literal
- `\x` escapes any char

Errors: `IllegalArgumentException` for null pattern, unclosed `[`, empty class, reversed range (`[z-a]`), trailing backslash; `NullPointerException` for null path.

Assumptions: `**/` is only special at the start of a segment (start of pattern or after `/`); elsewhere `**` is plain `**`. Matching must be polynomial (memoised): `*a*a*a*a*b` against 5000 `a`s must finish fast.

Success: `mvn -q -B test` passes.
