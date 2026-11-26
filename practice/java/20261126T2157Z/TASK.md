# 20261126T2157Z practice: patch toolkit (ae.patch)

Level 20 capstone. Topic: text diff / unified patch / three-way merge. Overlap check: `benchmark/java/j18-diff` is a bare minimal line diff returning an edit script; this round adds the unified-diff text format (writer + strict parser), patch application with offsets/reject/reverse, and diff3-style merging. No skill covers it (`grep -ril "diff3\|unified" skills` finds only the VCS skill's mention).

## Design
Text is a `List<String>` of lines without terminators (assumption: every file ends with a newline; `\ No newline at end of file` is rejected by the parser).

- `LineDiff.diff(a, b)` -> `List<Edit>` (`Keep|Delete|Insert`), minimal (|a|+|b|-2*LCS), common prefix/suffix trimmed, delete before insert inside a change block.
- `UnifiedDiff.format(a, b, context, oldLabel, newLabel)` -> text byte-identical to GNU `diff -u -U<context> --label ...` (counts omitted when 1, start = line before when count 0, hunks merged when the gap between changes is <= 2*context, empty string when equal).
- `UnifiedDiff.parse(text)` -> `Patch(labels, hunks)`; strict: header counts must equal body lines, hunks ascending and non-overlapping, every body line starts with ' ', '+' or '-'. Throws `PatchFormatException` with a message naming the line.
- `Patcher.apply(lines, patch, maxOffset)`: a hunk applies when its old lines (context + deletes) match exactly (fuzz 0) at the expected position + running offset, else at the nearest position within `maxOffset` (try +1,-1,+2,-2... after offset 0; the delta found shifts later hunks). Rejected hunks are collected, never half-applied. `Patch.reverse()` swaps old/new.
- `Merge3.merge(base, ours, theirs)` -> `List<Chunk>` (`Stable(lines)` | `Conflict(ours, base, theirs)`). Change regions of both diffs (vs base) are clustered when they overlap or touch (share a base position or are adjacent) -- adjacency rule fixed by the GNU diff3 oracle. A cluster touched by one side takes that side; both sides producing identical text take it; otherwise a Conflict. `render(chunks, oursLabel, theirsLabel)` writes `<<<<<<< ours` / `=======` / `>>>>>>> theirs` like `diff3 -m`.

## Success criteria
- `mvn -q -B test` passes. Tests: literals; differential against installed GNU `diff -u`, `patch` and `diff3 -m` on seeded random inputs with unique lines (so the minimal script is unique); algebraic properties (apply(a, diff(a,b)) = b, reverse round trip, merge identities, swap symmetry); strict-parser rejection tests with controls; mutation pass.
