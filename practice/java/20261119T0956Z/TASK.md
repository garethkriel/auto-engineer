# Level 20 capstone: `ae.datalog` — a small Datalog engine

## Design
Source text -> `Parser` -> `Program` (facts, rules, queries) -> `Analyzer` (arity, safety, stratification) -> `Engine` (bottom-up evaluation per stratum, naive or semi-naive) -> `Result`.

Syntax: `parent(tom, bob).` facts (ground); `anc(X,Z) :- parent(X,Y), anc(Y,Z).` rules; `?- anc(tom, X).` queries.
Body items: positive atom, `not atom` (negation), `X != Y` (inequality). `%` starts a line comment.
Constants: lowercase identifiers, integers (`-?[0-9]+`), double-quoted strings (`\"` escape). All constants are strings internally, so `"abc"` equals `abc`. Variables start with an uppercase letter or `_`; each bare `_` is a fresh anonymous variable.

Semantic checks (all raise `DatalogException` with 1-based line and column of the offending rule/atom):
- fixed arity per predicate; facts must be ground;
- safety: every head variable and every variable in `!=` must occur in a positive body atom; variables in a negated atom must too, except anonymous `_` ones (existential);
- stratification: a predicate may not depend on itself through negation.

Evaluation: strata are computed as the least assignment with `s(head) >= s(p)` for positive and `s(head) >= s(p)+1` for negated dependencies; strata run in order to a fixpoint. Semi-naive mode joins each rule once per same-stratum positive body atom using only the previous round's delta for that atom. Naive mode re-joins everything each round (oracle).
`Result`: `relation(pred)` sorted tuples, `answers(i)` bindings of the i-th query (named non-`_` variables, sorted), `joinWork()` count of body-join results produced (to compare modes), `strata()`.

## Success criteria
`mvn -q -B test` passes: parser/positions, safety/stratification errors, classic programs (ancestor, same-generation, win-move with negation), naive == semi-naive on random programs, transitive closure vs Floyd-Warshall oracle, semi-naive does strictly less join work than naive on a chain.

Overlap check vs `benchmark/java/`: j35-minisql is SQL, j08-graph is graph search, j22-regex/j28-script are other languages; no benchmark task is a logic-programming fixpoint engine.
