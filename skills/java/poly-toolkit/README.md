# poly-toolkit

- **Description:** Polynomials over the prime field Z_998244353 (`ae.poly`): `Mod` arithmetic, iterative `Ntt`, immutable normalised `Poly` (schoolbook/NTT multiply, truncate, derivative, Horner eval, Newton power-series `inverse(n)`, `divMod` via reversal, O(n²) Lagrange `interpolate`), `Shamir` (k,n) secret sharing with `recover`/`isConsistent`, and `Recurrence` (Berlekamp-Massey, `nthTerm` as x^n mod characteristic polynomial for n up to 2^62).
- **Language:** java
- **Tags:** ntt, polynomial, modular-arithmetic, newton-iteration, secret-sharing, berlekamp-massey, linear-recurrence, differential-testing
- **Source round:** practice/java/20261124T2256Z (level 20)
- **Pitfalls:** products of reduced values stay below 2^60 so `long` is safe; `inverse(n)` needs a non-zero constant term and a doubling loop truncated to the exact n; `divMod` reverses both operands to the *normalised* degree (leading coefficient non-zero gives a non-zero reversed constant term); BM's connection polynomial must be copied before the update when the length grows; `nthTerm` handles n < order directly and order 0 as the zero sequence.
- **Mutation check:** 13 mutants, 9 killed outright plus 1 killed after adding a threshold test (`isConsistent` `<=` vs `<`); 3 survivors are equivalent (naive/NTT cutoff and the `n < order` fast path only change speed; a nearly-no-op denominator mutant).
