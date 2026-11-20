# mini-sql-engine

- **Language:** Java 21, standard library only, package `ae.minisql`
- **Tags:** sql, parser, query engine, hash join, predicate pushdown, three-valued logic, group by, transactions, differential testing
- **Source round:** `practice/java/20261120T1156Z`

In-memory SQL engine: `Database.execute(sql)` / `executeScript`. Supports CREATE/DROP TABLE (INT/TEXT/BOOL, PRIMARY KEY,
NOT NULL), INSERT/UPDATE/DELETE (atomic per statement), BEGIN/COMMIT/ROLLBACK, and SELECT with INNER/LEFT JOIN, WHERE,
GROUP BY/HAVING, COUNT/SUM/MIN/MAX (+DISTINCT), DISTINCT, ORDER BY (alias/ordinal), LIMIT/OFFSET, LIKE, IN, IS NULL.
`Planner.NAIVE` (nested loops) and `Planner.OPTIMIZED` (hash join on equi-conditions, WHERE pushdown) return identical
results; `lastPlan()` exposes the plan for tests.

## Reusable ideas
- Keep a deliberately naive executor as the oracle for the optimized one and compare them on seeded random queries
  (ORDER BY every output column makes results total); measure the outcome mix (non-empty, hash joins, left joins, pushdown).
- Bind first: qualify every column against the scope and run all static checks (unknown/ambiguous, grouping, nested
  aggregates) before reading rows, so both planners fail identically even on empty tables; qualified records give
  structural equality for matching GROUP BY expressions.
- Pushdown is unsafe into the right side of a LEFT JOIN; NULL join keys never match (skip them on build and probe).
- Statement atomicity: build the new row list, check constraints on the final state, then swap the reference.
- LIKE with `%` via two-pointer star backtracking is polynomial (no regex, no exponential blowup).
