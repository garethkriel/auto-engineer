# Task (level 8: strategy + builder)

Build an immutable, fluent `SelectBuilder` that produces parameterised SQL, with a pluggable `Dialect` strategy.

- `Dialect` (interface): `quote(identifier)`, `placeholder(index)` (1-based), `pagination(limit, offset)`.
  Implementations: `ANSI` (`"x"`, `?`, `LIMIT n OFFSET m`), `MYSQL` (backticks), `POSTGRES` (`$1`, `$2`), `SQLSERVER` (`[x]`, `OFFSET m ROWS FETCH NEXT n ROWS ONLY`, which needs ORDER BY).
- `SelectBuilder.from(table)`, `.columns(...)` (default `*`), `.where(column, Op, value)` (AND-combined; `Op` = EQ, NE, LT, LE, GT, GE, LIKE), `.whereIn(column, values)`, `.orderBy(column, ascending)`, `.limit(n)`, `.offset(n)`, `.build(Dialect)` -> `Query(String sql, List<Object> params)`.
- Values are never inlined: they go to `params`.
- Identifiers must match `[A-Za-z_][A-Za-z0-9_]*` (optionally `schema.table`, each part quoted) else `IllegalArgumentException`; `*` allowed in columns.
- Builder is immutable: every method returns a new builder; earlier builders are unaffected.

Success: `mvn -q -B test` passes with tests written first.

Assumptions: empty `whereIn` list is rejected (IAE); limit>=1, offset>=0; offset without limit is rejected for ANSI/MYSQL/POSTGRES? No: simply ignored is surprising, so `build` throws IllegalStateException for offset without limit; SQLSERVER throws IllegalStateException when pagination is used without ORDER BY.

Overlap check: benchmark/java names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex): none concerns SQL building. No overlap.
