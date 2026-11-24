# 20261124T1356Z — ae.rope: persistent balanced rope with line index

## Task (level 20 capstone, standard library only)
Build `ae.rope.Rope`, an **immutable, persistent text rope** (balanced binary tree of small leaf
strings) with O(log n) concat/split/insert/delete/charAt, a cached newline count per node giving a
line/column index, and a streaming substring search.

## Overlap check
`benchmark/java/` lists j01-roman .. j35-minisql: no rope/text-buffer/line-index task
(j12-fileindex is file indexing, j18-diff is line diff, j03-lru a cache). Existing skill
`undo-redo-editor` is command-based undo, not a rope; no skill holds a balanced tree of strings.

## Design
- `Node` = `Leaf(String, 1..LEAF_MAX chars)` or `Branch(left, right)`; every node caches
  `len`, `nl` (number of `'\n'`) and `height` (leaf = 0). `LEAF_MAX = 32` (package-private, so tests
  cross leaf boundaries with short texts).
- AVL-style balance: `join(a,b)` descends the taller tree's inner spine and rebalances with single or
  double rotation on the way up (`|h(l)-h(r)| <= 1` everywhere). `split(i)` recursion uses `join`.
  Two adjacent leaves whose combined length fits are merged in `join`.
- Immutable: every operation returns a new `Rope` sharing subtrees; old versions never change.
- Line model: only `'\n'` terminates a line (assumption; `'\r'` is an ordinary char). `lineCount() =
  newlines + 1`; the terminator belongs to the line it ends but is not part of `line(k)`.

## API (package `ae.rope`)
`Rope implements CharSequence`
- `static Rope empty()`, `static Rope of(CharSequence)`
- `int length()`, `char charAt(int)`, `String substring(int from, int to)`, `Rope subSequence(from,to)`
- `Rope concat(Rope)`, `Rope insert(int at, CharSequence)`, `Rope delete(int from, int to)`,
  `Rope replace(int from, int to, CharSequence)`, `Split split(int at)` where
  `record Split(Rope left, Rope right)`
- `int lineCount()`, `int lineStart(int line)`, `int lineOf(int offset)` (offset 0..length),
  `String line(int line)`, `Position positionOf(int offset)`, `int offsetOf(int line, int column)`
  with `record Position(int line, int column)` (0-based; column may equal the line length)
- `int indexOf(String needle)`, `int indexOf(String needle, int from)` (KMP across leaf boundaries;
  empty needle returns `min(max(from,0), length)`; -1 when absent)
- `equals`/`hashCode` by content (`hashCode() == toString().hashCode()`), `toString()`
- package-private: `int depth()`, `int leafCount()`, `void check()` (invariant checker)
- Bad index -> `IndexOutOfBoundsException` (also `from > to`); `null` argument -> `NullPointerException`.

## Success criteria
1. Literal tests for every method, including boundaries (0, length) and empty rope.
2. Seeded differential test vs `StringBuilder` (insert/delete/replace/concat/split/slice) with
   `check()` after every operation and line-index cross-checks against a naive oracle.
3. Balance tests: 100k single-char appends and prepends keep depth <= 1.45*log2(leaves+2)+2 and full leaves.
4. Persistence test: old versions unchanged after edits.
5. `indexOf` differential vs `String.indexOf` incl. needles spanning leaf boundaries.
6. Hand mutation check of the core rules (kill-check script run once, results in the lesson).
7. `mvn -q -B clean test` green; `python3 eval/validate_round.py` green.
