# Task 20261124T0256Z — level 20 capstone: polynomials over prime fields

## Statement
Package `ae.polyfp` (standard library only): arithmetic and factorisation of polynomials over GF(p), plus Shamir secret sharing built on it.

* `Poly` (immutable, coefficients low→high, normalised, prime `p < 2^31` checked with `BigInteger.isProbablePrime`):
  `of(p, coeffs...)`, `zero/one/x`, `degree()` (-1 for zero), `coeff(i)`, `lead()`, `add/sub/neg/mul(Poly)/mul(long)`,
  `divMod(d)` (`{q, r}`), `mod`, `monic()`, `static gcd` (monic, gcd(0,0)=0), `powMod(BigInteger e, Poly m)`, `eval(x)`,
  `derivative()`, `static interpolate(p, xs, ys)` (unique polynomial of degree < n), `isIrreducible()` (Rabin test), `factor()`.
* `factor()` returns `Factorization(long unit, List<Factor> factors)`; `Factor(Poly poly, int multiplicity)`.
  Factors are monic irreducible, distinct, sorted by (degree, then coefficients high→low); `unit` = leading coefficient.
  Pipeline: squarefree decomposition (including the `f' = 0` case, p-th root of the exponent), distinct-degree, Cantor–Zassenhaus
  equal-degree splitting with a fixed seed. Only odd p (even p → `IllegalArgumentException`); zero polynomial → `IllegalArgumentException`.
* `Shamir`: `split(p, secret, k, n, Random)` → `List<Share>` (x = 1..n, requires 1 ≤ k ≤ n < p, 0 ≤ secret < p),
  `combine(p, shares)` → secret via Lagrange at 0; duplicate x or empty list → `IllegalArgumentException`.

## Design decisions / assumptions
* Mixed-field operations throw `IllegalArgumentException`. Equality is structural (same p, same coefficients).
* Rabin test: monic f of degree n irreducible iff x^(p^n) ≡ x (mod f) and gcd(x^(p^(n/q)) - x, f) = 1 for each prime q | n. Degree 0 and zero are not irreducible; degree 1 is.
* `combine` with fewer than k shares returns *a* value (information-theoretically meaningless); tests check perfect secrecy instead of an error.

## Overlap check
`benchmark/java/` (j01–j35) has no polynomial/finite-field/secret-sharing task (nearest: j23-segtree, j27-primes, j19-allocate: unrelated). `skills/java/` has `fraction`, `prime-sieve-number-theory`, `erasure-coded-blob-store` (GF(256) only, no polynomial factoring).

## Oracles
* brute-force: roots by trying all x, irreducibility by trial division with all monic polys of degree ≤ n/2, division identity `a = q·b + r`.
* published counts of monic irreducibles: (1/n)Σ μ(n/d)p^d — p=3: n=2→3, n=3→8, n=4→18, n=5→48; p=5: n=2→10, n=3→40.
* factor output re-multiplied equals input; every factor passes the brute-force irreducibility oracle.
* Shamir: every k-subset reconstructs; k-1 shares are consistent with every possible secret.

## Success
`mvn -q -B clean test` green; `python3 eval/validate_round.py` passes.
