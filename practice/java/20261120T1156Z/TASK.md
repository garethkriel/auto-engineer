# Task: ae.minisql — a small in-memory SQL engine (level 20 capstone)

Overlap check: no SQL-engine task in `benchmark/java/` or `skills/java/` (only `sql-select-builder`, a string builder).

## Goal
`ae.minisql.Database`: parse and execute a SQL subset over in-memory tables, with two interchangeable
execution strategies (`Planner.NAIVE` nested loops; `Planner.OPTIMIZED` hash joins + predicate pushdown)
that must return identical results. Standard library only.

## Language
- DDL: `CREATE TABLE t (c INT|TEXT|BOOL [PRIMARY KEY] [NOT NULL], ...)` (at most one PRIMARY KEY, implies NOT NULL), `DROP TABLE t`.
- DML: `INSERT INTO t [(cols)] VALUES (..),(..)`, `UPDATE t SET c=e,.. [WHERE]`, `DELETE FROM t [WHERE]`. Each statement is atomic.
- `BEGIN` / `COMMIT` / `ROLLBACK` (snapshot; DDL included; nested BEGIN is an error).
- `SELECT [DISTINCT] items FROM t [[AS] a] {[INNER|LEFT] JOIN t2 [[AS] b] ON expr} [WHERE] [GROUP BY exprs] [HAVING] [ORDER BY e [ASC|DESC],..] [LIMIT n [OFFSET m]]`.
- Items: `*`, `t.*`, `expr [AS alias]`. Aggregates `COUNT(*)`, `COUNT/SUM/MIN/MAX([DISTINCT] e)`.
- Expressions: literals (INT, 'text' with '' escape, TRUE/FALSE/NULL), columns (`c` or `t.c`), `+ - * / %`, `||`, comparisons,
  `AND OR NOT` with SQL three-valued logic, `IS [NOT] NULL`, `[NOT] IN (..)`, `[NOT] LIKE` (`%`, `_`).
- Names and keywords case-insensitive. Types: INT = long (overflow -> SqlException), TEXT, BOOL.

## Semantics decisions (assumptions)
- WHERE/ON/HAVING keep a row only when the condition is TRUE (not NULL).
- NULL sorts first ascending, last descending; ORDER BY is stable; ORDER BY may name a select alias or a 1-based ordinal.
- GROUP BY treats NULLs as one group; non-aggregated expressions must equal a GROUP BY expression (or be built from them); any aggregate without GROUP BY gives exactly one group (even over zero rows: COUNT=0, others NULL).
- Static errors (unknown/ambiguous column, bad grouping, nested aggregate, aggregate in WHERE/ON) are raised before any row is read, so both planners fail identically.
- Comparing different types is a SqlException. Division/modulo by zero is a SqlException. The *order* in which row-level errors fire is unspecified.
- All parse errors are `SqlException` with `line L, col C` in the message.
- Equi-join keys that are NULL never match (also in the hash join).

## Success criteria
- Hand-computed literal tests for every feature; error-message tests.
- Differential tests: randomized schemas/data/queries (no `/`, `%`) give identical results under both planners; `LIKE` agrees with a `java.util.regex` oracle; aggregate/sort results agree with a stream-based oracle.
- Plan tests assert OPTIMIZED really uses `hash-join` and pushdown, and NAIVE does not.
- Atomicity tests (failed UPDATE/INSERT leaves table unchanged; ROLLBACK restores DDL+data).
- `mvn -q -B clean test` passes.
