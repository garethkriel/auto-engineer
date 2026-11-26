# cfg-toolkit

- **Language:** java (21, JDK only)
- **Tags:** compiler, control-flow-graph, dominators, dominance-frontier, natural-loops, liveness, dce, register-allocation, graph-colouring
- **Source round:** `practice/java/20261126T2056Z` (task statement in its `TASK.md`)

A three-address IR (`Function.parse` / `toText`), `Cfg`, `Dominators` (Cooper-Harvey-Kennedy, frontiers, tree), `Loops`
(natural loops, depth, reducibility), `Liveness` (block and per-instruction), `Interp` (step-budgeted reference interpreter),
`Dce` (liveness-driven) and `RegAlloc` (interference graph, Briggs optimistic colouring, rename).

Tests: 56, run with `mvn -q -B test`. Randomised tests compare against independent oracles (dominance by cut reachability,
frontiers by definition, T1/T2 reducibility, brute-force path liveness, interpreter equivalence, a second colouring
implementation). The round's 53-mutant pass is `mutate.py` in the source round.

Reuse notes: dominance frontier of an entry with a back edge contains the entry; copies do not interfere with their source;
parameters interfere pairwise and with variables live into the entry; DCE is liveness-only (self-sustaining loop updates stay).
