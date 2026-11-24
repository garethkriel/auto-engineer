# persistent-rope

- **Description:** `ae.rope.Rope`, an immutable persistent text rope (AVL-balanced tree of leaves of at most 32 chars) with O(log n) `concat`/`split`/`insert`/`delete`/`replace`/`charAt`/`subSequence`, a cached newline count giving a line index (`lineCount`, `lineStart`, `lineOf`, `line`, `positionOf`, `offsetOf`), KMP `indexOf` that streams across leaf boundaries, and a package-private invariant checker (`check`, `depth`, `leafCount`).
- **Language:** java
- **Tags:** rope, text-buffer, avl, persistent, immutable, line-index, kmp, differential-test
- **Source round:** practice/java/20261124T1356Z
- **Reuse notes:** `join` (descend the taller spine, rebalance with one or two rotations on the way up) plus recursive `split` is the whole algorithm; `LEAF_MAX` is package-private so tests cross leaf boundaries with short strings. The checker is tested by hand-building corrupt trees through the package-private `Rope(Node)` constructor. Lines are terminated by `'\n'` only.
