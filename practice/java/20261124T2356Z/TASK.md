# Task 20261124T2356Z — game-tree search: negamax alpha-beta with a transposition table

Level 20 capstone (small end-to-end system). Package `ae.search`, standard library only.

**Overlap check.** Listed all 35 ids in `benchmark/java` (j01-roman … j35-minisql). None is a game or tree search
(nearest: j08-graph, graph search; j32-fsm, a state machine). Existing skill `chess-rules` only generates legal moves; it has no search.

## Design
- `Position` — immutable game position seen from the side to move:
  `moves()`, `play(move)`, `status()` (`ONGOING`, `MOVER_LOST`, `MOVER_WON`, `DRAW`), `heuristic()` (ongoing positions,
  from the mover's view, `|h| < WIN/2`), `key()` (64-bit hash; equal keys must mean equal ply, status, moves and heuristic), `ply()`.
- Terminal scores depend only on the position: `MOVER_LOST` = `-(WIN - ply)`, `MOVER_WON` = `+(WIN - ply)`, `DRAW` = 0, `WIN = 10_000`.
  So faster wins and slower losses score better, and a transposition table needs no mate-distance adjustment.
- `Minimax.value(pos, depth)` — plain depth-limited negamax with a node counter: the oracle.
- `TranspositionTable(entries)` — fixed size, key verified on probe, replacement: empty slot, different key, or same key with depth >= stored depth.
- `AlphaBeta(ttEntries, ordering)` — fail-soft negamax with EXACT/LOWER/UPPER bounds, TT move first then children sorted by
  `-child.heuristic()`, `search(root, depth)`, `analyse(root, depth)` (exact value of every root move) and
  `iterate(root, maxDepth)` (iterative deepening, stops at a proven result). `ttEntries == 0` disables the table.
  The table is never used to cut off at the root, so a best move always exists for an ongoing root.
- Games: `ConnectK(rows, cols, k, gravity)` (tic-tac-toe = 3x3x3 without gravity, Connect Four = 6x7x4 with gravity,
  `parse(...)` builds hand-made positions) and `SubtractionNim(stones, maxTake, misere)`.
- `Perft.count(pos, depth)` counts nodes of the game tree.

## Success criteria
1. Tic-tac-toe game tree: 255168 complete games and 549946 nodes; value 0 (draw) under every search configuration.
2. Without a table alpha-beta returns exactly the `Minimax` value at every depth on random positions of several boards;
   with a table (any size, even 1 entry) it returns the exact value when the depth reaches the end of the game.
3. The returned best move achieves the returned score; `analyse` equals per-move minimax.
4. Alpha-beta visits fewer nodes than minimax; table and ordering reduce nodes further.
5. Subtraction games: mover loses iff `n % (m+1) == 0` (normal) or `== 1` (misère), with literal scores.
6. `mvn -q -B clean test` passes.

## Assumptions
- Heuristic bound is `< WIN/2`; a score with `|s| > WIN/2` is a proven win or loss.
- `depth >= 1` for `search`; a terminal root returns the terminal score and `NO_MOVE`.
