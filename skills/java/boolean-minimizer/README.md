# boolean-minimizer

- **Description:** Exact two-level boolean minimisation in package `ae.qmc`: `Implicant` (value/mask cube), `Minimizer.primeImplicants` (Quine–McCluskey level merging), `Minimizer.minimize(n, minterms, dontCares[, maxNodes])` returning the minimum (terms, literals) sum of products via essential primes, row/column dominance, an independent-rows lower bound and exact branch-and-bound, and `Sop` (eval, canonical `A'B + C` text, `parse`).
- **Language:** java (21, standard library only)
- **Tags:** boolean-minimization, quine-mccluskey, prime-implicants, set-cover, branch-and-bound, dominance, dont-care
- **Source round:** `practice/java/20261125T0857Z`
- **Reuse notes:** restricting a cover to primes is exact for (terms, literals) cost. Row dominance drops a row only under the candidate set it was dropped with, so child searches must inherit the `dead` candidate set (resurrecting a pruned prime gave an uncovered minterm). Dense random 8+ variable functions have huge cyclic cores: keep the node budget and let it throw. QM enumerates all 3^(n-1) sub-cubes of a big cube, so n=16 is slow.
- **Tests:** `mvn -q -B test` (27 tests; brute-force DFS oracle that uses neither primes nor dominance, literal regression cases found with a mutant, 26 mutants checked, only the pure-optimisation sibling-exclusion survived).
