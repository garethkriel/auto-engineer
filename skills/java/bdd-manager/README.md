# bdd-manager

Reduced ordered binary decision diagrams in plain Java 21.

- **Language:** java
- **Tags:** bdd, boolean-functions, canonical-form, ite, quantification, model-counting, formula-parser, invariant-checker
- **Source round:** `practice/java/20261125T1156Z`

## What it gives you
- `ae.bdd.Bdd(numVars, maxNodes)`: int node ids, unique table (equal functions are equal ints), memoised `ite` with `not/and/or/xor/imp/iff` on top, `restrict`, `exists`, `forall`, `compose`, `andExists`, `eval`, `satCount` (BigInteger), `anySat` (0/1/2 = don't care), `cubes`, `support`, `size`, `check()`.
- `ae.bdd.Expr.parse(bdd, text, names)`: `<->`, `->` (right assoc), `|`, `^`, `&`, `!`, parentheses, `0/1/true/false`; errors start with `col N:` and the name list is restored on failure.
- Variable order is the index; no GC, a node limit guards blow-up.

## Lessons baked into the tests
- Expected node counts come from an oracle that counts distinct subfunctions per level, not from hand arithmetic (a hand count was wrong in the source round).
- Each `check()` rule has a hand-corrupted manager, including "child at the same level" on the lo and hi side separately.
- Equivalent mutants: redundant early exits in `iteRec`, the `g==f`/`h==f` normalisations and over-eager `restrict` recursion cannot change results.

Run: `mvn -q -B test`.
