# Task 20261125T0857Z — Quine–McCluskey boolean minimiser (level 20 capstone)

## Understand
Package `ae.qmc`, standard library only. Minimise a boolean function of `n` variables (1..16) given as
a list of minterms (must be 1) and don't-cares (free), to a sum-of-products with the **exact minimum cost**,
cost = (number of terms, then number of literals), compared lexicographically.

Overlap check: the 35 `benchmark/java` directory names (j01-roman … j35-minisql) contain nothing about
boolean minimisation, prime implicants or set cover. No existing skill covers it either.

## Design
- `Implicant(value, mask)`: bits set in `mask` are don't-care positions; `value` is normalised to `value & ~mask`.
  Variable `i` (A, B, ...) is bit `n-1-i`, so `A` is the most significant bit. Canonical order: by value, then mask.
- `Minimizer.primeImplicants(n, minterms, dontCares)`: classic level-by-level combination (group by mask, merge
  pairs differing in one bit); an implicant that never merges is prime. Sorted canonically.
- `Minimizer.minimize(n, minterms, dontCares[, maxNodes])`: only primes covering at least one minterm are candidates
  (replacing any implicant by a prime containing it never raises cost, so restricting to primes is exact).
  Reduction by essential primes, then exact branching on the uncovered minterm with the fewest candidates,
  with branch-and-bound on (terms, literals). A "node" is one branching call; exceeding `maxNodes` throws
  `IllegalStateException`. Problems solved by essentials alone use zero nodes. Default budget 2,000,000.
- `Sop(n, terms)`: sorted distinct terms; `eval`, `literalCount`, `toString` (`A'B + C`, `0`, `1`), `parse`.
- Input validation: n outside 1..16, a term outside `[0, 2^n)`, a duplicate within a list, or a term in both lists
  -> `IllegalArgumentException`. Input order never affects the result.
- Empty minterm list -> `0`. If minterms+don't-cares fill the space -> `1`.

## Success criteria
- Literal tests (textbook example with its four primes, cyclic-core function, parity, constants, validation).
- Differential test: independent exact brute-force oracle (DFS over every implicant that avoids the off-set,
  no use of primes or essentials) agrees on cost for random functions, n <= 5, with and without don't-cares.
- Property tests: result valid (covers all minterms, avoids off-set), each prime really prime and all care points covered.
- Budget behaviour, determinism under input shuffling, round-trip parse, time-bounded n=10 run.
- A mutation pass over the main code with each surviving mutant analysed.
- `mvn -q -B clean test` green, `python3 eval/validate_round.py` ok.
