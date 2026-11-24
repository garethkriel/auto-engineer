# Task 20261124T0956Z — `ae.diff`: Myers diff, unified diff patches and three-way merge

Level 20 capstone. Overlap check: `benchmark/` has no diff/merge task as far as the task titles go; skills `dir-snapshot-diff` (directory trees) and `rsync-delta` (rolling-checksum block delta) are different algorithms.

## Scope (package `ae.diff`, line-based, lines carry no terminator)
1. `Diff.diff(a, b)` -> minimal edit script (`KEEP`/`DELETE`/`INSERT`) via Myers O(ND). `Diff.distance`, `Diff.regions` (maximal change runs `[aStart,aEnd) -> replacement`).
2. `UnifiedDiff.format(oldName,newName,a,b,context)`; `UnifiedDiff.parse(text)` -> `Patch`; `Patch.apply(lines)` strict (every context/deleted line must match; hunks ordered, non-overlapping, header numbers consistent); `Patch.reverse()`.
3. `Merge3.merge(base, ours, theirs)` -> list of `Clean`/`Conflict` chunks tiling the base; regions from both sides conflict when their base ranges intersect or both start at the same base index (touching, non-empty ranges are *not* a conflict); identical changes on both sides merge cleanly; `render` writes conflict markers.

## Assumptions
- No "\ No newline at end of file" support: text is a list of lines; format terminates each with `\n`; parse ignores `\ ...` marker lines.
- Unified-diff empty-range convention: start is the line *before* (0 at top) when the length is 0.

## Success criteria
- Edit-script length equals `n + m - 2*LCS` (independent DP oracle) on seeded random inputs; classic ABCABBA/CBABAC has D = 5.
- `apply(parse(format(a,b,k)),a) == b` for k in 0..4; hunk splitting threshold exact (gap 2k merges, 2k+1 splits).
- GNU `patch` applies our output and our parser/applier handles `diff -u` output (guarded by tool availability).
- Merge: oracle by construction on separated edits, symmetry, degenerate cases, tiling invariant, comparison with `git merge-file` on unique-line fixtures (guarded).
- `mvn -q -B clean test` green, plus a hand mutation check of the main rules.
