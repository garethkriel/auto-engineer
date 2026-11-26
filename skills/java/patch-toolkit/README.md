# patch-toolkit

- **Language:** java (package `ae.patch`, standard library only)
- **Tags:** diff, unified-diff, patch, diff3, three-way-merge, differential-testing, parser
- **Source round:** practice/java/20261126T2157Z

Minimal line diff (`LineDiff`, prefix/suffix trimmed, delete before insert), GNU-identical unified diff writer and strict parser (`UnifiedDiff`), `Patcher` (exact context, offset search +1,-1,+2.. carried to later hunks, rejects collected, `Patch.reverse()`), and `Merge3` (diff3 -m style: regions of both sides that overlap or touch form one cluster; identical changes merge cleanly; conflict text has ours / base / theirs sections).

Tests: literals, strict-parser rejections with controls, differential tests against installed GNU `diff`, `patch`, `diff3` (skipped via `assumeTrue` when absent; generators use fresh unique lines so the minimal script is unique), algebraic properties, 21-mutant pass with no survivors.

Gotchas: with GNU diff, `-u -U2` still gave 3 context lines, so pass only `-U<n>`; GNU `diff3 -m` prints identical changes on both sides as a base/theirs conflict (not copied); a 40000-line perf test is what proves prefix/suffix trimming.
