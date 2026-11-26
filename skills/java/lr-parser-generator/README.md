# lr-parser-generator

- **Language:** Java 21 (standard library only), package `ae.lr`
- **Tags:** parsing, LR, LALR, SLR, grammar, FIRST/FOLLOW, conflicts, table-driven parser
- **Source round:** practice/java/20261126T1657Z

Grammar text (`A -> x y | z | ε`) to FIRST/FOLLOW/nullable/productive/reachable analysis, then ACTION/GOTO tables in
`Mode.SLR`, `Mode.LALR` or `Mode.LR1`, conflict reports (`Conflict`, `LrTable.describe`) and a table-driven `LrParser`
returning iterative-safe `Tree`s or `LrParseException(position, found, expected)`.

Key facts: LALR states == LR(0) states; canonical LR(1) of the dragon-book expression grammar has 22 states, LALR 12.
`S -> L = R | R` is LALR but not SLR; `S -> aEa | bEb | aFb | bFa` (E, F -> e) is LR(1) but not LALR.

Tests: literals, exhaustive language-oracle differential, 400 seeded random grammars. Run `mvn -q -B test`.
