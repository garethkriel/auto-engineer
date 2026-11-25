# Task: `ae.bdd` — reduced ordered binary decision diagrams (level 20 capstone)

Overlap check: read the 35 `benchmark/java` directory names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is a boolean-function manipulation library. `j07-expr` evaluates arithmetic text; here the formula text only feeds a canonical-form manager. Existing practice rounds `qmc` (minimiser) and `logic` (netlists) do not use BDDs.

## Assumptions
- Standard library only, Java 21. No garbage collection of nodes (a manager is short-lived); a node limit guards against blow-up.
- Variable order is the variable index: variable 0 is the root-most level. Reordering is out of scope; ordering effects are shown by building the same function under two index assignments.

## Design
- `Bdd(int numVars, int maxNodes)`: nodes are ints. `FALSE = 0`, `TRUE = 1`. A non-terminal node has `(var, lo, hi)` with `lo != hi`, `var < var(lo)`, `var < var(hi)`. A unique table (`record Key`) makes the diagram canonical: equal functions are equal ints. Exceeding `maxNodes` throws `IllegalStateException("node limit ...")`; nodes made before the failing call stay valid, the manager remains consistent.
- Operations: `var(i)`, `nvar(i)`, `not`, `and`, `or`, `xor`, `imp`, `iff`, `ite`, `restrict(f, v, value)`, `exists(f, vars...)`, `forall(f, vars...)`, `compose(f, v, g)`, `andExists(f, g, vars...)`, `eval(f, boolean[])`, `satCount(f)` (BigInteger over all `numVars`), `anySat(f)` (assignment `byte[]` with 0, 1 or 2 = don't care, or `null` for FALSE), `cubes(f)` (paths to TRUE as strings of `0`,`1`,`-`, in lexicographic low-first order), `support(f)`, `size(f)` (reachable non-terminal nodes), `check()`.
- `check()` verifies every node: `redundant` (lo == hi), `order` (child var not greater than parent var), `duplicate` (two nodes with same triple), `range` (child id out of range); throws `IllegalStateException` whose message starts with that word.
- `Expr.parse(Bdd, String, List<String> names)`: grammar (loosest first) `<->`, `->` (right assoc), `|`, `^`, `&`, prefix `!`, atoms: identifiers `[A-Za-z_][A-Za-z0-9_]*`, `0`, `1`, `true`, `false`, parentheses. New identifiers are appended to `names` (variable index = position in `names`); a name not present when `names` already holds `numVars` entries is an error. Errors: `IllegalArgumentException` whose message starts with `col N:` (1-based).

## Success criteria
- `mvn -q -B clean test` passes.
- Literal checks: parity of n variables has 2n-1 nodes; `(x0&x1)|(x2&x3)|(x4&x5)` has 6 nodes under adjacent pairing and 14 (= 2^(n+1)-2) when each pair is split (x0&x3)|(x1&x4)|(x2&x5) (hand count: 1+2+4 for the x levels, then 4+2+1 subfunctions that depend on y0, y1, y2); queens counts 10, 4, 40, 92 for n = 5..8; precedence and associativity literals.
- Independent oracles: truth tables for random expression trees (eval, satCount, restrict, exists, forall, compose); a subfunction-counting oracle for node counts; an adder equivalence check with counterexample extraction.
- Corrupted-structure tests for every `check()` rule; a mutation pass over the implementation.
