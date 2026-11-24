# rb-tree-map

- **Description:** Order-statistic left-leaning red-black tree map (`ae.rbt.RbTreeMap`): put/get/remove with Sedgewick delete (moveRedLeft/moveRedRight/balance), floor/ceiling/lower/higher, `rank`, `select`, inclusive `countRange`, `entries(lo,hi)`, `pollFirst`, comparator support, and a package-private `check()` invariant checker (root-red, red-right, double-red, black-height, order, size) plus `height()`.
- **Language:** java
- **Tags:** red-black-tree, llrb, ordered-map, order-statistics, rank-select, balanced-tree, invariant-checker, differential-testing
- **Source round:** practice/java/20261124T2056Z (level 20)
- **Pitfalls:** delete must re-compare the key after `rotateRight` because `h` changed; every rotation must maintain subtree sizes; the checker uses open (lo, hi) bounds so a key violating an ancestor bound is caught; tests compare to `TreeMap` with `check()` after every mutation.
- **Mutation check:** 15 mutants, 13 killed; the 2 survivors are equivalent (rotation top size is always recomputed by `balance`; the `c == 0` early return in `floorKey` is only an optimisation).
