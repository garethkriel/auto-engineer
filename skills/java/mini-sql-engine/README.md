# mini-sql-engine

- **Description:** In-memory SQL engine (`ae.minisql.Database`): lexer, recursive-descent parser to a sealed AST, three-valued-logic evaluator, INNER/LEFT joins, GROUP BY/HAVING, ORDER BY (NULLs first asc), DISTINCT, LIMIT/OFFSET, atomic INSERT/UPDATE/DELETE, PRIMARY KEY/NOT NULL, BEGIN/COMMIT/ROLLBACK via snapshot of the catalog.
- **Language:** java (21, standard library only)
- **Tags:** sql, parser, ast, sealed-interfaces, three-valued-logic, joins, aggregation, transactions, differential-testing
- **Source round:** practice/java/20261119T0256Z (level 20 capstone)
- **Reuse notes:** statements are atomic because they validate a replacement row list and then swap it in; a snapshot is just a copy of the table map. `List.of(rowArray)` spreads an `Object[]`: use `Collections.singletonList(row)`. The test suite includes a random-predicate oracle (`DifferentialTest`) worth copying for any expression language.
