# Task (level 1, topic: arrays)

Implement `ae.ArrayOps` with static methods:
- `int[] rotateRight(int[] a, int k)`: new array rotated right by k (k may be negative or larger than length); empty array returns empty; input not mutated.
- `OptionalInt secondLargestDistinct(int[] a)`: second largest distinct value, empty if fewer than 2 distinct values.
- `int longestRun(int[] a)`: length of longest run of equal consecutive values (0 for empty).

Null arrays throw `IllegalArgumentException`.
Assumption: no external input; pure functions. Success: `mvn -q -B test` passes.
