# exact-linear-algebra

- **Language:** Java 21 (standard library only), package `ae.linalg`
- **Tags:** linear algebra, rational arithmetic, Gauss-Jordan, LU, determinant, Bareiss, characteristic polynomial, eigenvalues, Gram-Schmidt
- **Source round:** practice/java/20261126T1757Z

Exact (BigInteger-backed) `Rational` and immutable `Matrix`; `Elimination` (rref, rank, determinant, inverse, nullspace,
column-space basis, `solve` -> sealed `Solution` Unique/Infinite/Inconsistent), `Bareiss` integer determinant, `Lu`
(PA = LU, first-non-zero pivot, works on singular input), `CharPoly` (Faddeev-LeVerrier, rational eigenvalues with
multiplicity via the rational-root theorem, eigenspaces) and `GramSchmidt` (orthogonal, not normalised).

Key facts: nullspace vectors are in free-column order with 1 in their free column; `Infinite.particular` is 0 in every
free column; `List.of(array)` spreads the array, so wrap a single `Rational[]` with `Collections.singletonList`.
Tests are seeded randomised cross-checks (det three ways, A*inv(A)=I, rank-nullity, Cayley-Hamilton, eigenvectors,
orthogonality). Limits: `rationalEigenvalues` needs scaled coefficients below 2^62.
