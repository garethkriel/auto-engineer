# Task: compiler middle-end toolkit (`ae.cfg`)

Level 20 capstone (recorded level is capped at 10 by the validator).

Overlap check (done by reading `ls benchmark/java`: j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs,
j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges,
j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock,
j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql): none is a
control-flow-graph / dominator / dataflow / register-allocation task. `j08-graph` is plain graph search and `j28-script` a
script interpreter; neither does dataflow. Existing skills (`minilang-toolchain`, `rv32im-toolchain`) compile/execute
languages but have no CFG analyses (`grep -ril "dominator\|liveness" skills` finds only an unrelated word in paxos).

## IR (package `ae.cfg`)
Text form, one item per line; `#` starts a comment; blank lines ignored; identifiers are `[A-Za-z_][A-Za-z0-9_]*`:
```
func NAME(p1, p2)        # header, params may be empty
label:                   # a block; the first block is the entry
  d = const 5            # Const(dst, value: long, may be negative)
  d = add a, b           # Bin(dst, op, a, b), op in add sub mul lt eq (lt/eq give 1 or 0)
  d = copy s             # Copy(dst, src)
  br c, L1, L2           # Br: c != 0 -> L1 else L2
  jmp L                  # Jmp
  ret v                  # Ret
```
- `Instr` is a sealed interface with records `Const, Bin, Copy, Br, Jmp, Ret`; `def()` (Optional dst), `uses()` (operand
  names in order), `isTerminator()`, `targets()`.
- `Block(label, instrs)` and `Function(name, params, blocks)` are records that defensively copy their lists.
- `Function.parse(text)` and `toText()` round-trip (`parse(f.toText()).equals(f)`).
- Validation (`IllegalArgumentException`; parse messages start with `line N:` for syntax errors): at least one block; labels
  unique; every block non-empty, its LAST instruction and only that one a terminator; branch targets exist; params unique;
  every used variable is a parameter or defined by some instruction; an instruction before the first label is an error.
- Semantics: all values are `long` (wrapping). `Interp.run(f, args, maxSteps)` returns `Optional<Long>` (empty once more than
  `maxSteps` instructions executed); reading a variable not yet assigned throws `IllegalStateException`; wrong arg count
  throws `IllegalArgumentException`.

## Analyses
- `Cfg(f)`: block index = position in `f.blocks()`; `succ(b)`/`pred(b)` (de-duplicated, in first-occurrence order),
  `reachable(b)`, `rpo()` (reverse post-order of reachable blocks, entry first), `index(label)`.
- `Dominators(cfg)` (Cooper-Harvey-Kennedy): `idom(b)` is -1 for the entry and for unreachable blocks; `dominates(a, b)` is
  reflexive and false if either is unreachable; `frontier(b)` (sorted set; the entry counts as having an implicit extra
  predecessor, so an entry with a back edge is in its own frontier); `children(b)` in the dominator tree, ascending.
- `Loops(cfg, dom)`: back edge `t -> h` iff `h` dominates `t`. One `Loop(header, blocks, tails)` per header
  (`blocks` = header plus every block that reaches a tail without passing through the header, a sorted set; `tails` ascending),
  `loops()` ordered by header, `depth(b)` = number of loops containing `b`, `reducible()` = removing back edges leaves the
  reachable graph acyclic.
- `Liveness(cfg)`: `liveIn(b)`, `liveOut(b)` (sorted sets, all blocks including unreachable), `liveAfter(b)`: for each
  instruction the set of variables live immediately after it.
- `Dce.run(f)`: drops unreachable blocks, then repeatedly removes non-terminator instructions whose destination is not live
  after them, until nothing changes. Liveness-based only: a variable that is only used by its own redefinition in a loop
  stays (documented limitation).
- `RegAlloc`: `interference(f)` -> `Map<String,Set<String>>` over every variable. A definition `d` interferes with every other
  variable live after it, except that `d = copy s` does not interfere with `s`; all parameters interfere pairwise and with every
  variable live into the entry. `color(graph, k)` -> `Allocation(Map<String,Integer> regs, Set<String> spilled)` by
  Briggs-style optimistic colouring: repeatedly push the node with the smallest degree `< k` (ties: smaller name); if none,
  push the node of largest degree (ties: smaller name) as a potential spill; then pop, giving each node the lowest register
  not used by an already-coloured neighbour, spilling it if none is free. `allocate(f, k)` = `color(interference(f), k)`;
  `rename(f, allocation)` rewrites every variable to `r<reg>` (throws `IllegalArgumentException` if anything spilled).
  `k < 1` throws `IllegalArgumentException`.

## Success criteria
- `mvn -q -B clean test` passes: literal hand-derived fixtures for each analysis (diamond, counted loop, irreducible,
  unreachable, entry self-loop), plus seeded randomised tests against independent oracles: dominators by "removing `a` cuts
  `b` off", frontiers from the definition, loop bodies by avoidance reachability, reducibility by T1/T2 reduction, liveness by
  brute-force path search, DCE and register renaming by interpreter equivalence on random programs (with a negative control
  that proves a wrong allocation is detected).
- A mutation pass (`mutate.py`) over the main sources kills every non-equivalent mutant.
