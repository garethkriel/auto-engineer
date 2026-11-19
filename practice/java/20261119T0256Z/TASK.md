# Task: ae.minisql — a mini in-memory SQL database (level 20 capstone)

Run 20261119T0256Z. Language: Java 21, JUnit 5, standard library only.

## Goal
A small SQL engine: lexer, recursive-descent parser producing a sealed AST, and an executor with three-valued logic,
joins, grouping, ordering and statement/transaction atomicity. Public API (package `ae.minisql`):

```java
public final class Database {
    public Result execute(String sql);              // exactly one statement; trailing ';' allowed
    public List<Result> executeScript(String sql);  // ';'-separated statements, run in order
}
public record Result(List<String> columns, List<List<Object>> rows, int affected) {}
public final class SqlException extends RuntimeException { public int position(); } // 0-based offset in the sql text, -1 if none
```
Values are `Long` (INT), `String` (TEXT), `Boolean` (BOOL), `Double` (only from AVG), `null` (NULL).

## Design
- Lexer: identifiers (case-insensitive, stored lowercase), integer literals, `'text'` with `''` escape, symbols
  `( ) , ; . * + - / % = != <> < <= > >= ||`, `--` line comments. Unterminated string -> SqlException at the quote.
- Statements: `CREATE TABLE t (c INT|TEXT|BOOL [NOT NULL] [PRIMARY KEY], ...)`, `DROP TABLE t`,
  `INSERT INTO t [(cols)] VALUES (...), (...)`, `UPDATE t SET c = e, ... [WHERE e]`, `DELETE FROM t [WHERE e]`,
  `SELECT [DISTINCT] items [FROM t [alias] {[INNER|LEFT] JOIN t [alias] ON e}] [WHERE e] [GROUP BY cols] [HAVING e]
  [ORDER BY e [ASC|DESC], ...] [LIMIT n [OFFSET m]]`, `BEGIN`, `COMMIT`, `ROLLBACK`.
- Expressions, lowest to highest precedence: OR, AND, NOT, comparison/IS [NOT] NULL/[NOT] LIKE/[NOT] IN/[NOT] BETWEEN,
  `||`, `+ -`, `* / %`, unary `-`, primary (literal, column `[t.]c`, call, parenthesised). Literals NULL TRUE FALSE.
  Functions: COUNT(*) COUNT(e) SUM MIN MAX AVG (aggregates), UPPER LOWER LENGTH ABS COALESCE (scalar).
- Semantics: NULL propagates through arithmetic/comparison/LIKE; AND/OR/NOT use three-valued logic (AND/OR short-circuit);
  WHERE/HAVING/ON keep a row only when the predicate is TRUE. `x IN (...)`: TRUE if any equal, else NULL if x or an element is
  NULL, else FALSE. LIKE: `%` any run, `_` one char, case-sensitive. Integer ops use exact arithmetic (overflow and division by
  zero -> SqlException). Comparing different types is an error (Long/Double compare numerically).
- Result column label: the alias, else the column name (lowercase), else the exact source text of the expression.
- ORDER BY: ascending puts NULL first, descending last; stable; a bare name matching a select alias refers to that output
  column; an integer literal n refers to output column n. DISTINCT keeps first occurrences. Select without FROM yields one row.
- Aggregates: aggregate query = any aggregate call or a GROUP BY. Plain column refs outside aggregates must appear in GROUP BY.
  No GROUP BY on an empty input still yields one group (COUNT=0, SUM/MIN/MAX/AVG=NULL). Groups appear in first-seen order.
  Aggregates are rejected in WHERE/ON and cannot nest. SUM/COUNT of INT are Long; AVG is Double; NULLs are ignored.
- Atomicity: every statement is all-or-nothing (a failing multi-row INSERT/UPDATE changes nothing). PRIMARY KEY = NOT NULL + unique.
  BEGIN snapshots the catalog; ROLLBACK restores it (DDL included); nested BEGIN, or COMMIT/ROLLBACK outside a transaction, is an error.

## Success criteria
`mvn -q -B clean test` passes: literal-expectation tests per feature, error-position tests, and a seeded differential test
that generates random predicates with a Java three-valued-logic oracle and checks WHERE, ORDER BY and aggregates.
