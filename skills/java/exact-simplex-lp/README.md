# exact-simplex-lp

- **Language:** Java 21 (standard library only)
- **Tags:** linear-programming, simplex, bland, rational-arithmetic, duality, farkas, certificates, parser
- **Source round:** practice/java/20261119T2356Z

Package `ae.lp`: exact LP solving with self-checking answers.

- `Rational`: BigInteger fractions (normalised, `parse("1/4")`, `dot`).
- `LpParser`: `max:`/`min:` text format, all variables >= 0, errors with 1-based line numbers (`LpParseException`).
- `Simplex.solve(model)`: two-phase tableau, Bland's rule (Beale's cycling example tested), redundant equality rows dropped
  after phase 1. Returns sealed `Solution`: `Optimal(value, x, duals)`, `Infeasible(farkas)`, `Unbounded(point, ray)`.
- `CertificateChecker.check(model, solution)`: independent exact verifier (empty list = valid). Duality conventions are in the class.
- Duals/Farkas come from solving `B^T w = c_B` on the final basis (`LinearSolve`), mapped back with the row scale sign.

Reusable ideas: certificate-per-outcome design, vertex-enumeration oracle for tiny random LPs, kill-checking sign conventions.
