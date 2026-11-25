# game-tree-search

- **Language:** java (JDK 21, standard library only)
- **Tags:** game-tree, negamax, alpha-beta, transposition-table, zobrist, iterative-deepening, move-ordering, minimax-oracle
- **Source round:** practice/java/20261124T2356Z

Package `ae.search`: negamax alpha-beta (`AlphaBeta`) with an optional fixed-size transposition table
(`TranspositionTable`: EXACT/LOWER/UPPER bounds, key verified on probe), move ordering by child heuristic,
`analyse` (exact value per root move) and `iterate` (iterative deepening, stops at a proven result).
`Position` is the game interface (`moves`, `play`, `status`, `heuristic`, `key`, `ply`); games provided:
`ConnectK` (tic-tac-toe, Connect Four, any rows/cols/k, `parse` for hand-built positions) and `SubtractionNim`
(normal and misere). `Minimax` is the unpruned oracle and `Perft` counts game-tree nodes.

Design rules worth reusing
- Terminal scores are `+-(WIN - ply)`, a function of the position only, so table entries need no mate-distance fix.
- The key must determine ply/status/moves/heuristic (Nim includes the ply); a test checks this on random playouts.
- Never cut off at the root with the table, or a best move may be missing; keep one table per game type.
- Tests: exact equality with plain minimax (no table, any depth; with table at full depth), table sizes down to 1,
  one table reused across searches and across increasing depths, node-count ordering, 14-mutant check.
