# Round 20261126T1657Z: LR parser generator (SLR / LALR(1) / canonical LR(1))

Level 20 capstone, package `ae.lr`.

## Overlap check
`benchmark/java/` names: j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql.
`j07-expr` / `j28-script` evaluate expressions/scripts with hand-written parsers; this task builds *parser tables from a grammar* (FIRST/FOLLOW, item sets, conflicts). No overlap with an existing skill either (`earley-parser`, `peg-engine` are different algorithms).

## Design
- `Grammar.parse(text)`: lines `A -> x y | z | ε` (`%empty` also accepted), `#` comments, first LHS is the start symbol, symbols that never appear on a left side are terminals. Empty alternatives must be written `ε`. Reserved: `$`, `$accept`, `->`, `|`, `ε`, `%empty`. Production 0 is the augmented `$accept -> S`; user productions are numbered from 1 in text order.
- Analyses: `nullable`, `first(symbol)`, `follow(nonterminal)` (FOLLOW of start contains `$`), `productive()`, `reachable()`.
- `LrTable.build(grammar, Mode)` with `Mode` = `SLR` (LR(0) automaton, reduce on FOLLOW), `LR1` (canonical), `LALR` (canonical states merged by core, lookaheads united). States are numbered in breadth-first discovery order (state 0 = closure of `$accept -> . S`), visiting symbols in grammar symbol order.
- Cells hold a list of `Action` (`Shift(state)`, `Reduce(production)`, `Accept`), shifts first then reduces ascending. A cell with more than one action is a `Conflict(state, symbol, kind, actions)`, kind `SHIFT_REDUCE` if it contains a shift else `REDUCE_REDUCE`. Conflicts are listed ordered by (state, symbol).
- `LrParser(table)` refuses a table with conflicts (`IllegalArgumentException`). `parse(tokens)` returns a `Tree` (leaf = terminal, node = nonterminal with its production index) or throws `LrParseException(position, found, expected)`; `position` is the index of the offending token (`tokens.size()` for end of input, found = `$`), `expected` = sorted terminals having an action in the detecting state. A token equal to `$` is rejected with `IllegalArgumentException`.
- `Tree.toString()` is an s-expression: leaf prints its symbol, node prints `(sym child...)`, an ε node prints `(A)`.

## Success criteria
- Literal tests: FIRST/FOLLOW tables, dragon-book state counts (expression grammar: SLR 12, LALR 12, LR1 22; `S->CC` grammar: 7/7/10), SLR-not-LALR grammar (`S -> L = R | R`), LALR-not-LR(1) grammar, dangling else, `E -> E + E | id`.
- Exhaustive differential test of the table-driven parser against a fixed-point language oracle (every string up to a length bound), including tree validity (yield equals input, each node is a production).
- Random grammars: LALR state count == SLR state count; SLR conflict-free => LALR conflict-free => LR1 conflict-free; when LALR and LR1 are both conflict-free their parsers agree on trees and error positions; conflict-free LR1 parsers match the oracle.
- Mutation pass over the production code (script `mutate.py`).
- `mvn -q -B clean test` passes.
