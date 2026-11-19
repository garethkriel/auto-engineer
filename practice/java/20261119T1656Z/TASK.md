# CDCL SAT solver with independently checked results (level 20 capstone)

Overlap check (benchmark/java): j01..j35 = roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours,
rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql. None is boolean satisfiability or
proof checking. Existing skill `sudoku-bitmask` is a bitmask search, not a SAT solver. No overlap.

## Task
Package `ae.sat`, standard library only. A conflict-driven clause-learning (CDCL) SAT solver whose answers are
verified by independent code, so no result is trusted on belief.

## Design
* `Cnf(int numVars, List<int[]> clauses)`: DIMACS literals (`+v` / `-v`, 1..numVars). Validates literals, copies input.
* `Dimacs.parse(String)` / `Dimacs.write(Cnf)`: comments `c`, header `p cnf V C`, clauses may span lines and end
  with `0`; a line starting with `%` ends input (SATLIB). Errors are `IllegalArgumentException` starting `line N:`.
* `Solver(Cnf)`: two-watched-literal propagation, first-UIP conflict analysis, non-chronological backjumping,
  VSIDS-style activity with decay, phase saving, Luby restarts. `solve()` and `solve(long conflictLimit)` return a
  `Result(Status status, boolean[] model, List<int[]> proof, long conflicts, long decisions, long propagations)`.
  `Status {SAT, UNSAT, UNKNOWN}`. `model[v]` is the value of variable v (index 0 unused). On UNSAT the `proof` is
  the sequence of learned clauses ending with the empty clause `new int[0]`.
* `ModelChecker.satisfies(Cnf, boolean[])`: every clause has a true literal.
* `RupChecker.check(Cnf, List<int[]>)`: each proof clause must follow by reverse unit propagation (assume the
  negation of every literal; unit propagation over the original clauses plus earlier proof clauses must conflict),
  and the proof must end with the empty clause.
* `Encodings`: `pigeonhole(pigeons, holes)`, `queens(n)`, `colouring(vertices, edges, colours)`,
  `random3Sat(vars, clauses, seed)`.

## Success criteria
* DIMACS parse/write round trip and every error case.
* Hand-made CNFs (empty formula, empty clause, units, contradictory units, tautologies, duplicate literals).
* Differential test against brute-force enumeration on 400 seeded random 3-SAT instances near the phase transition;
  every SAT model passes `ModelChecker`, every UNSAT proof passes `RupChecker`.
* Known answers: pigeonhole (n+1 into n) UNSAT, queens SAT for n=1,4..10 and UNSAT for 2,3, Petersen graph 3-colourable,
  K4 not 3-colourable.
* `RupChecker` rejects non-implied clauses, proofs without the empty clause and truncated proofs.
* Conflict limit returns `UNKNOWN`; same input twice gives identical results (determinism).
