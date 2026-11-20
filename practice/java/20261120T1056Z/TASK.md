# Level 20 capstone: chess rules engine (`ae.chess`)

## Overlap check
`benchmark/java/` holds j01-roman ... j35-minisql (roman numerals, word frequency, LRU, CSV, intervals, LCS,
expression evaluation, graphs, bank, counter, streams, file index, rate limit, JSON, topo sort, ranges, cache,
diff, allocator, cron, spreadsheet, regex, segment tree, queue, business hours, rwlock, primes, script, kv log,
mini server, ranking, fsm, pricing, vector clocks, mini SQL). A chess move generator matches none of those.
`skills/java/` has no chess skill. Different domain, different algorithms.

## Task
Build a complete, standard-library-only chess rules engine, test-first, verified against the published
perft node counts (the objective oracle) plus independent invariants.

### Design (packages: single package `ae.chess`)
- `Move` record `(from, to, promo)`; squares are `rank*8+file`, a1 = 0. UCI text `e2e4`, `e7e8q`.
  Castling is the king moving two files; en passant is derived (pawn moves diagonally to the ep square).
- `Position` (mutable board with `make`/`unmake` returning an `Undo`):
  FEN parse (strict validation, `IllegalArgumentException` with a message) and `toFen`,
  `legalMoves()`, `isAttacked`, `inCheck`, `isCheckmate`, `isStalemate`, `insufficientMaterial`, `copy`,
  `repetitionKey` (placement, side, castling, ep only when a legal ep capture exists).
- `Perft.count(pos, depth)`.
- `San`: `toSan(pos, move)` (disambiguation, `x`, `=Q`, `O-O`, `+`/`#`) and `parse(pos, text)`.
- `Search`: material-only `negamax` (oracle), `alphaBeta` with move ordering, `shortestMate(pos, maxMoves)`.
- `Game`: play SAN/UCI, status (ongoing, checkmate, stalemate, insufficient material, fifty-move,
  threefold repetition), movetext export and import.

### Assumptions
- FEN ep square is recorded after every double pawn push (lichess style); the repetition key only counts it
  when a legal ep capture exists.
- Castling rights in a FEN must be backed by king and rook on their home squares, otherwise rejected.
- SAN parsing accepts exactly the canonical SAN of a legal move (plus optional `+ # ! ?` annotations).
- Fifty-move draw at halfmove clock >= 100; a checkmate on that move wins priority over the draw.

### Success criteria
`mvn -q -B clean test` passes, including: perft for the six standard test positions (node counts from the
chess programming wiki), mirrored-position perft, random-playout make/unmake round trips, SAN round trips for
every legal move of random positions, and negamax == alpha-beta values.
