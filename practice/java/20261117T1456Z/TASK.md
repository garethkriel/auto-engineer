# Task: bitmask Sudoku solver (level 12, performance)

Package `perf`, class `Sudoku` (all static, non-instantiable). Overlap check against `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): no sudoku/constraint-solving task.

A puzzle is a 81-char string, row-major; `'1'..'9'` are givens, `'.'` or `'0'` is empty.

- `int[] parse(String)`: 81 ints (0 = empty). `IllegalArgumentException` for null, wrong length, bad character, or givens that conflict (same digit twice in a row/column/box).
- `Optional<String> solve(String)`: first solution as an 81-digit string, empty if none. Givens are preserved.
- `int countSolutions(String, int limit)`: number of solutions, stopping at `limit` (`limit >= 1`, else IAE).
- `boolean isValidSolution(String)`: complete grid, each row/col/box has 1-9 exactly once.

Performance: use `int` bitmasks for rows/cols/boxes, pick the empty cell with the fewest candidates (`Integer.bitCount`), iterate candidates with `Integer.numberOfTrailingZeros`/`x & (x-1)`. A famously hard puzzle must solve well under 2 s; a failed call must leave no state (pure functions).

Assumptions: standard 9x9 only; inputs are untrusted strings so all validation is in `parse`.
Success: `mvn -q -B test` passes.
