# chess-rules

- **Language:** Java 21, standard library only, package `ae.chess`
- **Tags:** chess, move generation, perft, FEN, SAN, make/unmake, alpha-beta, mate search, game status
- **Source round:** `practice/java/20261120T1056Z`

A complete chess rules engine: `Position` (strict FEN parse/print, `make`/`unmake` with an `Undo` record,
legal moves incl. castling, en passant, promotion, check/mate/stalemate, insufficient material,
`repetitionKey`), `Perft`, `San` (canonical SAN with disambiguation, parse by matching legal moves),
`Search` (material negamax oracle, alpha-beta, `shortestMate`) and `Game` (status incl. threefold
repetition and fifty-move rule, movetext import/export).

## Reusable ideas
- Verify a move generator with published perft counts for the six standard positions, plus colour-mirrored
  FENs (catches white/black asymmetry bugs) and `divide` sums.
- Derive castling/en passant from the move shape instead of storing flags; filter pseudo-legal moves by
  making them and testing king safety.
- Parse SAN by generating the canonical SAN of every legal move and comparing; this is unambiguous by construction.
- Test alpha-beta by asserting equal scores with a plain negamax oracle.

Tests: `mvn -q -B test` (116 tests, about 10 s).
