# semver-resolver

- **Description:** `ae.deps` package-manager core: strict semver `Version` with spec precedence, npm-style `Constraint` (`^ ~ >= <= > < = x * ||`, partials, spaced operators, prerelease allowance rule), `Registry` port with `InMemoryRegistry`/`TextRegistry`, a complete backtracking `Resolver` (forward checking, preferred/locked versions, search budget, conflict messages naming requirers), dependencies-first install order and an atomic `Lockfile`.
- **Language:** java
- **Tags:** semver, dependency-resolution, backtracking, constraints, lockfile, prerelease
- **Source round:** practice/java/20261118T2257Z
- **Reuse notes:** desugar every range to comparators; derive exclusive upper bounds as `X-0` flagged synthetic so they exclude prereleases of the bound without granting prerelease access. Treat "unknown package" as a search failure message, not an exception inside the search, because another version of the requirer may avoid it. Test completeness with a brute-force enumeration of all assignments on tiny seeded registries.
