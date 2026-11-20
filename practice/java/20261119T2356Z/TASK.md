# 20261119T2356Z: exact simplex LP solver with self-checking certificates (level 20 capstone)

## Understand
Build `ae.lp`: a small linear-programming system, standard library only.

* `Rational`: exact fractions on `BigInteger` (normalised, positive denominator).
* `LpParser`: text format -> `LpModel`, errors carry the 1-based line number.
* `Simplex`: exact two-phase tableau simplex with Bland's rule. Result is a sealed
  `Solution`: `Optimal` (values, objective, dual vector), `Infeasible` (Farkas vector) or
  `Unbounded` (feasible point + improving ray).
* `CertificateChecker`: independent exact checker that validates any `Solution` against the
  model using only the model data (never the tableau). Soundness of the solver is judged by it.

All variables are implicitly `>= 0`. Constraints are `<=`, `>=`, `=` with a rational rhs.

## Text format
```
# comment
max: 3 x + 2 y          (or min:)  first non-blank, non-comment line
c1: x + y <= 4          optional "name:" prefix; default name is c<index starting at 1>
x + 3y <= 6             terms: [+|-] [rational] [identifier]; "3y" == "3 y"; repeats are summed
1/4 x4 - 8 x5 >= -2
```
Rational = `int` or `int/int` (no decimals). Constraint rhs is a single signed rational.
Variables are numbered by first appearance (objective first). Parse errors throw
`LpParseException(line, message)`: missing objective, bad token, missing operator, no variables
in a constraint, zero denominator, duplicate constraint name, a second objective.

## Duality conventions (checker)
Max problem: `y_i >= 0` for `<=`, `y_i <= 0` for `>=`, free for `=`; need `y^T A >= c`, value `y^T b`.
Min problem: signs mirrored (`<=` -> `y<=0`, `>=` -> `y>=0`), need `y^T A <= c`.
Infeasible: `y` with the max-style signs, `y^T A >= 0` and `y^T b < 0`.
Unbounded: feasible `x0`, ray `d >= 0` with `A d` obeying homogeneous constraints and `c.d` improving.

## Success criteria
* Textbook cases (including Beale's cycling example) solved exactly.
* Every random instance: solver output passes `CertificateChecker`; optimal values equal a
  brute-force vertex-enumeration oracle; infeasible <=> oracle finds no feasible vertex.
* Checker rejects hand-corrupted certificates (kill checks).
* `mvn -q -B clean test` passes.

## Overlap check
`benchmark/java/` names: roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex,
segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing,
vclock, minisql. None involves linear programming / simplex / rational arithmetic (j19 allocate
is a different greedy allocation task); no shared core algorithm.

## Assumption
No free or bounded variables beyond `>= 0` (keeps the dual conventions small).
