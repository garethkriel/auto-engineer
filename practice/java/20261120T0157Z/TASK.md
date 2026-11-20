# Task: `ae.peg` — a PEG grammar engine (level 20 capstone)

Overlap check (RULES): `benchmark/java/` names: roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank,
counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree,
queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql.
None is a PEG/packrat engine; `expr`, `json` and `regex` are the nearest by surface, so this round builds a
*grammar interpreter* (grammar text -> static analysis -> packrat matcher -> parse tree -> error report) and never
evaluates expressions, never builds a JSON value and never implements a regex engine.

## Design (written first)
Package `ae.peg`.
- `Expr` sealed interface: `Lit`, `Any`, `CharClass`, `Ref`, `Seq`, `Choice`, `Repeat(body, atLeastOne)`, `Opt`,
  `Pred(body, negative)`. `Expr.render` prints grammar syntax; reparsing the output gives an equal `Expr`.
- Grammar text: `Name <- expr`, rules continue over lines until the next `Ident <-`; `#` comments; `'lit'` or
  `"lit"` with escapes `\n \t \r \\ \' \"`; `[a-z_]`, `[^...]`; `.`; `( )`; `/` ordered choice; `* + ?`; `& !`.
  Syntax errors throw `GrammarException` with `line L, column C`.
- `Grammar` (immutable) = ordered rules, first rule is the start rule. Construction analyses it and throws
  `GrammarException` for: undefined rule, duplicate rule, `*`/`+` over a body that can match empty, left recursion
  (direct, indirect, via nullable prefixes or predicates; message shows the cycle `A -> B -> A`).
- `PegParser` packrat interpreter: memo keyed by (rule, position, inside-predicate?). `run(input)` returns
  `Result(node, evaluations, farthest, expected)`. `matchPrefix` = longest prefix PEG semantics (ordered choice is
  committed, repetition is possessive). `parse` requires full input; failure throws `PegParseException` with
  position, line, column and the sorted set of expectations at the farthest failure (failures inside predicates are
  suppressed; a failed `!x` records `not x`).
- `Node(rule, start, end, children)`: only rule invocations create nodes; `sexp(input)` renders the tree.
Limit: recursion depth follows nesting depth of the input (no explicit stack); characters are UTF-16 units.

## Success criteria
`mvn -q -B clean test` passes: grammar parser/render round trip, analysis errors, PEG semantics table, arithmetic and
JSON-shaped grammars, farthest-failure errors, packrat linear-evaluation bound, random differential tests
(regex oracle with atomic groups/possessive quantifiers; memo-on vs memo-off).
