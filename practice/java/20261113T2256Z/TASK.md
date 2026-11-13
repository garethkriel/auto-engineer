# Task (level 3, Standard library: Comparator, StringBuilder, Math, List)

Implement `ae.TextTable`, an immutable plain-text table formatter.

- `TextTable.of(List<String> headers, List<Align> aligns)`; `enum Align {LEFT, RIGHT}`. Sizes must match, headers non-empty, else `IllegalArgumentException`.
- `withRow(String... cells)` returns a NEW table with the row appended; wrong cell count -> `IllegalArgumentException`; null cell treated as "".
- `sortedBy(int column, boolean numeric, boolean descending)` returns a new table sorted by that column (stable). Numeric compares as `double`; cells that do not parse sort after all numeric ones (also when descending), keeping their relative order. Bad column -> `IllegalArgumentException`.
- `render()`: each column is as wide as its longest cell (header included); cells padded per alignment; columns joined by `" | "`; a separator line of `-` joined by `"-+-"` under the header; no trailing spaces on LEFT-aligned last column (trailing whitespace stripped per line); lines joined with `\n`, no trailing newline.

Assumptions: width = `String.length()`; no Unicode width handling.
Success: `mvn -q -B test` passes.
