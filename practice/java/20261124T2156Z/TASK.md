# Round 20261124T2156Z: optimal paragraph layout (Knuth-Plass style line breaking)

Level 20 capstone, package `ae.layout`. Standard library only.

## Overlap check
Listed all 35 `benchmark/java` ids. Closest are j13-ratelimit, j30-miniserver (different domain), j18-diff and j06-lcs (different DP). No benchmark is about text layout or line breaking. Existing skill `text-table` formats tables, not paragraphs. No overlap.

## Design
Input is a list of `Item`s (sealed interface, records):
- `Box(width, text)`: unbreakable content.
- `Glue(width, stretch, shrink)`: breakable space. Legal break only if the previous item is a `Box`. Requires `0 <= shrink <= width`, `stretch >= 0`.
- `Penalty(width, penalty, flagged)`: optional break with a cost. `penalty >= PENALTY_INF` (10000) means "never break here"; `penalty <= -PENALTY_INF` forces a break. `width` is added to the line only if the break is taken (hyphen). `flagged` marks a hyphen break.

The last item must be a forced break. Items between two breaks are one line: it starts at the first `Box` after the previous break (glue and penalties after a break are discarded) and ends before the break item. A break at a penalty adds its width.

Line fit, with natural width `W`, target width `L(line)` (a function of the zero-based line index, so shapes and indents work), total stretch `Y`, total shrink `Z`, `d = L - W`:
- `d == 0`: badness 0.
- `d > 0`: needs `Y > 0`, else infeasible. badness = `100*d^3 / Y^3` (integer floor division).
- `d < 0`: needs `-d <= Z`, else infeasible (overfull). badness = `100*(-d)^3 / Z^3`.
- Infeasible if badness > tolerance.

Demerits of a line (all `long`): `(linePenalty + badness)^2`, plus `p^2` if the break penalty `p >= 0`, minus `p^2` if `-INF < p < 0`, plus `flaggedDemerits` if this and the previous line both end at a flagged penalty. The layout minimises the sum. No fitness classes.

Algorithm: dynamic programming over active nodes `(break index, line number, previous line flagged)` keeping the cheapest per key, with pruning of nodes that can never fit again (`W - Z > L`, valid because `shrink <= width`) and deactivation of all nodes at a forced break.

API:
- `Breaker.breakLines(List<Item>, IntUnaryOperator lineWidth, Params)` returns `Layout(breaks, lines, totalDemerits)`; throws checked `LayoutException` if no feasible layout exists.
- `TextJustifier.justify(String text, int width, Function<String,int[]> hyphenator)` builds items (space = `Glue(1,1,0)`, hyphen = `Penalty(1,50,true)`, final fill glue), tries tolerance 100, 400, then unlimited, and renders monospaced lines: every line but the last is exactly `width` wide, extra spaces are spread left-first, the last line is left-aligned with single spaces.

## Success criteria
- Hand-derived fixtures give the literal breaks and demerits.
- A brute-force oracle (enumerate every subset of legal breaks, evaluate with its own simple evaluator) agrees on the minimum demerits over many seeded random instances including penalties, forced breaks, variable widths and tolerance limits, and the returned breaks evaluate to that minimum.
- Pruning is checked by a large paragraph finishing in bounded time.
- Rendered text is checked: line widths, word order, hyphen reassembly.
- Validation errors have messages that tests assert on.
- `mvn -q -B clean test` passes.
