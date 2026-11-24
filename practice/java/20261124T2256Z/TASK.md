# Task 20261124T2256Z — polynomial toolkit over Z_p (level 20 capstone)

Overlap check: listed all 35 `benchmark/java` ids (j01-roman … j35-minisql). None concerns polynomials,
number-theoretic transforms, secret sharing or linear recurrences. Nearest by theme: `j27-primes` (sieve),
unrelated algorithm. Existing skills `bigint-from-scratch` (Karatsuba) and `erasure-coded-blob-store` (GF(256))
differ in field and algorithms.

## Goal
A small end-to-end library in package `ae.poly`, prime field p = 998244353 (= 119·2^23 + 1, primitive root 3):

* `Mod` — field arithmetic on longs in [0,p): `add, sub, mul, pow, inv, norm`. `inv(0)` throws `ArithmeticException`.
* `Ntt` — in-place iterative transform `transform(long[] a, boolean invert)`; length must be a power of two
  (1..2^23) else `IllegalArgumentException`. Inverse includes the 1/n scaling.
* `Poly` — immutable polynomial, coefficients low→high, always normalised (no trailing zeros, values in [0,p)).
  `of(long...)`, `ZERO`, `degree()` (-1 for zero), `coeff(i)`, `add, sub, mul` (naive below 32 terms else NTT),
  `truncate(n)` (mod x^n), `derivative()`, `eval(x)` (Horner), `inverse(n)` (power-series inverse mod x^n by Newton
  iteration; constant term must be non-zero else `ArithmeticException`), `divMod(d)` returning `record QR(Poly q, Poly r)`
  (via reversal + power-series inverse; zero divisor → `ArithmeticException`), `interpolate(xs, ys)` (distinct xs
  else `IllegalArgumentException`).
* `Shamir` — `record Share(int x, long y)`; `split(secret, k, n, LongSupplier coeffs)` gives n shares at x=1..n of a
  random polynomial of degree < k with constant term = secret; `recover(shares)` Lagrange-at-0 over all given shares;
  `isConsistent(shares, k)` true iff the shares lie on one polynomial of degree < k. Preconditions: 1 ≤ k ≤ n < p,
  secret in [0,p), shares have distinct positive x.
* `Recurrence` — `berlekampMassey(long[] seq)` returns the shortest c with s[i] = Σ c[j-1]·s[i-j]; `nthTerm(c, init, n)`
  computes s[n] (n up to 2^62) as x^n mod the characteristic polynomial using `Poly`.

## Success criteria
`mvn -q -B clean test` passes with: literal hand-derived fixtures; naive-oracle differential tests (NTT mul vs
O(n²), divMod invariant, interpolation round trip, BM vs generated continuation); a Fibonacci(10^18) check against an
independent matrix power; Shamir information-theoretic check (k-1 shares are consistent with every candidate secret);
a size test (2^16-term product and a 50000/20000 division, checked at random evaluation points, within a time bound).

## Assumptions
No external libraries. Coefficient supplier values are reduced mod p by `Shamir`. Shares with more than k points are
allowed in `recover` (they are all interpolated, so a corrupted extra share changes the answer; `isConsistent` detects it).
