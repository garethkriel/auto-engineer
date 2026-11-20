# Round 20261120T0456Z — `ae.flow`: network-flow toolkit and shift planner (level 20)

## Task
Build a small end-to-end planning system, standard library only (Java 21), package `ae.flow`:

1. `FlowNetwork` — directed network with `long` capacities and `long` costs.
   - `maxFlow(s,t)` (Dinic, iterative so chains of 200 000 nodes do not overflow the stack), `minCut(s)` / `cutEdges(s)`,
   - `minCostFlow(s,t,limit)` (successive shortest paths, Bellman-Ford/SPFA, negative costs allowed, negative cycles rejected),
   - `verify(s,t)` (capacity bounds + conservation), `hasNegativeResidualCycle()` (optimality certificate), `reset()`.
2. `BipartiteMatching` — Hopcroft–Karp (iterative) plus König minimum vertex cover.
3. `Assignment` — Hungarian algorithm for rectangular (rows <= cols) cost matrices, negative costs allowed.
4. `ShiftPlanner` — text spec -> plan, solved as min-cost max-flow.

## Design
- Arcs are stored in paired arrays (`e` forward, `e^1` residual) with head/next adjacency. Edge id `i` = arc `2*i`.
- `maxFlow` returns the *additional* flow pushed; a second call returns 0. Sum of capacities leaving `s` is checked with
  `Math.addExact` before any mutation, so overflow fails without side effects.
- `minCostFlow` needs a zero-flow network (`IllegalStateException` otherwise) and no negative residual cycle at start.
  Its result is the min cost among flows of value `min(limit, maxflow)`. Arithmetic overflow resets the network and rethrows.
- Limits: `0 <= capacity <= Long.MAX_VALUE/4`, `|cost| <= 1e12`, no self-loops.
- Planner text format (`#` full-line comments and blank lines ignored; names are single tokens):
  ```
  worker <name> max <n>        # at most n shifts
  shift  <name> needs <n>      # wants n workers
  allow  <worker> <shift> cost <c>   # one worker fills a shift at most once
  ```
  Numbers: `max`/`needs` in 0..1 000 000, `cost` in -1 000 000..1 000 000. Declarations must precede use.
  `PlanParseException` carries the 1-based line number.
  Goal: fill as many slots as possible, then minimise total cost. Output order: assignments by worker declaration then shift
  declaration. `Plan.format()` prints `w -> s` lines, `unfilled: none` or `unfilled: s k, ...`, then `cost: N`.

## Success criteria
- `mvn -q -B clean test` passes in this folder.
- Oracles: textbook CLRS flow (23), brute-force enumeration for min-cost flow, permutation brute force for Hungarian,
  Kuhn matching + König cover check, subset enumeration for the planner, cross-engine (Hungarian vs min-cost flow).
- Overlap check: `benchmark/java/` contains no flow, matching or assignment task (j08-graph is shortest path, j15-topo ordering).
