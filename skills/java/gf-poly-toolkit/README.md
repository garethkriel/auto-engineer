# gf-poly-toolkit

- **Description:** polynomials over GF(p) (odd or even prime < 2^31): add/mul/divMod/gcd/powMod/eval/derivative, Lagrange interpolation, Rabin irreducibility test, full factorisation (squarefree incl. the f'=0 p-th-root case, distinct-degree, seeded Cantor–Zassenhaus; odd p only) and Shamir (k,n) secret sharing.
- **Language:** java (package `ae.polyfp`, standard library only)
- **Tags:** finite-field, polynomial, factorisation, cantor-zassenhaus, rabin, shamir, interpolation
- **Source round:** practice/java/20261124T0256Z
- **Tests:** 30, with brute-force oracles (trial-division irreducibility, root enumeration), published irreducible counts, product-of-factors identity, all k-subsets for Shamir. Mutation check: 8 of 11 mutants killed, 3 equivalent (`shiftRight(1)` on odd p^d, `merge` vs `put` since no factor is reported at two levels, `i` vs `i % p`), 1 malformed pattern.
