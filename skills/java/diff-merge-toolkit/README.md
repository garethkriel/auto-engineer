# diff-merge-toolkit

- **Language:** Java 21 (Maven, JUnit 5)
- **Tags:** diff, myers, unified-diff, patch, three-way-merge, diff3, text
- **Source round:** `practice/java/20261124T0956Z`

Line-based text diff stack in package `ae.diff`:

- `Diff.diff/distance/regions`: Myers O(ND) minimal edit script (O(D^2) memory), maximal change regions.
- `UnifiedDiff.format/parse` + `Patch.apply/reverse`: unified diff text, strict patch application (context, ordering, header consistency).
- `Merge3.merge`: diff3-style merge into `Clean`/`Conflict` chunks tiling the base; `render` writes conflict markers.

Tests: LCS-DP oracle for minimality, round trips for context 0..4, hunk-split threshold, GNU `patch`/`diff -u` interop, `git merge-file` agreement (guarded by tool availability), composition oracle for separated edits, symmetry properties.
Gotchas: git joins nearby conflicts (compare conflict presence, not count); touching non-empty base ranges merge cleanly here, git calls adjacent changes a conflict.
