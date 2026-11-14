# sql-select-builder

- **Description:** immutable fluent builder for parameterised SELECT statements with a pluggable `Dialect` strategy (ANSI, MySQL, Postgres `$n`, SQL Server `OFFSET/FETCH`). Values go only to bind params; identifiers are allow-list validated.
- **Language:** java
- **Tags:** builder, strategy, immutability, sql, validation
- **Source round:** 20261114T1456Z
- Run: `mvn -q -B test`
