# sudoku-bitmask

- **Description:** bitmask Sudoku solver (`perf.Sudoku`): 9-bit candidate sets per row/column/box, fewest-candidates cell selection with `Integer.bitCount`, lowest-bit iteration, solution counting with a limit, strict input validation.
- **Language:** java
- **Tags:** bit manipulation, backtracking, constraint solving, performance, time-bounded tests
- **Source round:** practice/java/20261117T1456Z
- **Pattern to reuse:** keep search state in a private per-call object (pure public API); build multi-solution fixtures by construction (blank all cells of two digits).
