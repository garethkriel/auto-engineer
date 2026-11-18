# Level 20 capstone: `ae.deps` — semver dependency resolver with lockfile

## Overlap check
`benchmark/java/` has j15-topo (topological sort), j19-allocate, j16-ranges, j17-cache, j35-minisql, ... None resolves version constraints (semver parsing, range algebra, backtracking over candidate versions, lockfiles). Topological order is only a small final step here.

## Task
Build a small package-manager core, stdlib only, package `ae.deps`:

1. `Version` (record): semver `major.minor.patch[-prerelease]`, strict parse (no leading zeros, no build metadata, `IllegalArgumentException` otherwise), semver 2.0 precedence (`Comparable`; numeric prerelease ids < alphanumeric ids, shorter list loses a tie), `toString`.
2. `Constraint`: npm-style ranges. Alternatives joined with `||`; inside one alternative space-separated comparators (AND). Supported: `*`/`x`/empty, `1`, `1.2`, `1.2.x`, `1.2.3`, `=1.2.3`, `>`, `>=`, `<`, `<=` with partial versions, `^`, `~`, an operator separated from its version by spaces (`>= 1.2`). A prerelease version matches an alternative only if one *explicit* comparator of that alternative has the same `major.minor.patch` and a prerelease. `^`/`~`/partial upper bounds never admit prereleases of the bound.
3. `Registry` port (`versions(name)`, `find(name, version)`); `InMemoryRegistry` adapter; `TextRegistry.parse` for lines `name@1.2.3: dep constraint, dep2 constraint` (`#` comments, blank lines).
4. `Resolver(registry).resolve(roots, preferred)`: picks exactly one version per package, every constraint from roots and from chosen packages satisfied, depends-closed. Prefers a *preferred* (locked) version when it satisfies all constraints, otherwise the highest. Complete: finds a solution iff one exists (backtracking). Failure -> `ResolutionException` whose message names the package and the requirers (`name@version` or `<root>`). Search budget (default 200_000 candidate attempts) -> `ResolutionException("search budget exceeded")`. Cycles between packages are allowed.
5. `Resolution`: `versions()` sorted map name -> version and `installOrder()` — dependencies before dependents (DFS post-order, names visited in sorted order; on a cycle the back edge is ignored).
6. `Lockfile`: text `name@version` per line sorted; `format(Resolution)`, `parse(String)` -> `Map<String,Version>`, `write(Path, Resolution)` (temp file + atomic move), `read(Path)`.

## Assumptions
- Constraint text of a dependency is the rest of the comma-separated segment after the name.
- Versions with prerelease are candidates only when the constraint admits them.
- Existing roots not in the registry -> `ResolutionException` "unknown package".

## Success
`mvn -q -B clean test` passes: table tests with literal expectations, plus a seeded brute-force oracle (enumerate every assignment on tiny random registries; the resolver must succeed iff an assignment exists, and results are validated independently), lock-preference tests, mutation checks.
