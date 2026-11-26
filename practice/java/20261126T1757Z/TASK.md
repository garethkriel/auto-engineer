# Round 20261126T1757Z: exact rational linear algebra (`ae.linalg`)

Level 20 capstone (python tools/curriculum.py java -> 20).

## Overlap check
`ls benchmark/java`: j01-roman j02-wordfreq j03-lru j04-csv j05-intervals j06-lcs j07-expr j08-graph j09-bank j10-counter j11-streams j12-fileindex j13-ratelimit j14-json j15-topo j16-ranges j17-cache j18-diff j19-allocate j20-cron j21-sheet j22-regex j23-segtree j24-queue j25-bizhours j26-rwlock j27-primes j28-script j29-kvlog j30-miniserver j31-ranked j32-fsm j33-pricing j34-vclock j35-minisql. None is linear algebra. Existing skills `fraction` (long fractions) and `poly-toolkit` are not matrices.

## Design
- `Rational`: immutable BigInteger num/den, den > 0, gcd 1, so `equals`/`hashCode` are plain field compares. `of(long)`, `of(long,long)`, add/sub/mul/div/negate/inverse/signum/compareTo/pow(int), `toString` = `n` or `n/d`. Division by zero -> `ArithmeticException`.
- `Matrix`: immutable rows x cols Rational grid (rows, cols >= 1, else IAE). `of(long[][])` (ragged -> IAE), `identity(n)`, `zero(r,c)`, `get`, `row`, `col`, `apply(Rational[])` (matrix times column vector), `plus/minus/times/times(Rational)/transpose/pow(int>=0)`, `isSquare`, `trace`, `equals`, `toString` (rows on lines, entries tab-free, space separated).
- `Elimination.rref(Matrix)` -> `Rref(matrix, pivotColumns)`: reduced row echelon form, pivots scaled to 1. `rank`, `determinant` (Gaussian with row swaps; square only), `inverse` (throws `SingularMatrixException`), `nullspace` (list of basis column vectors, one per free column, in free-column order, each with 1 in its free column), `columnSpaceBasis` (original columns at the pivot positions), `solve(A, b)` -> `Solution` sealed: `Unique(x)`, `Infinite(particular, nullBasis)` (particular has 0 in every free column), `Inconsistent`.
- `Bareiss.determinant(long[][])` fraction-free integer determinant returning BigInteger; used as an independent oracle.
- `Lu.decompose(Matrix)` -> `Lu(p, l, u, sign)` with partial "first non-zero" pivoting, P*A = L*U, L unit lower triangular, U upper; works for any square matrix (singular allowed: zero column skipped). `solve(b)` for non-singular.
- `CharPoly.coefficients(Matrix)`: Faddeev-LeVerrier, returns c[0..n] of det(xI - A) = sum c[k] x^k, c[n] = 1. `rationalEigenvalues(Matrix)` : distinct rational roots via rational-root theorem on the integer-scaled polynomial, ascending, each with algebraic multiplicity; `eigenspace(Matrix, lambda)` = nullspace(A - lambda I).
- `GramSchmidt.orthogonalize(List<Rational[]>)`: orthogonal (not normalised) basis of the span, dependent vectors dropped; `project(v, basis)`.

## Success criteria
- Literal tests (hand-derived inverse, rref, det, nullspace, charpoly of 2x2/3x3, Fibonacci matrix power).
- Property tests (seeded random small-integer matrices incl. rank-deficient ones): A*inv(A)=I, A*x=b for Unique, A*n=0 for each nullspace vector, rank + nullity = cols, det(AB)=det A*det B, det via Gauss == Bareiss == cofactor expansion, P*A=L*U, Cayley-Hamilton (charpoly(A)(A)=0), eigenvectors satisfy A v = lambda v, Gram-Schmidt orthogonality and span preservation, rank(A)=rank(A^T).
- Failure paths (ragged, mismatched dimensions, singular, 0 denominators).
- Mutation pass `mutate.py`; `mvn -q -B clean test` passes.
