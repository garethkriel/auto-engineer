# toml-subset-parser

- **Description:** TOML-subset config parser (`ae.tconf.Toml.parse`): comments, bare/quoted/dotted keys, basic and literal strings with escapes, ints (hex, underscores), floats, booleans, arrays, inline tables, `[table]` and `[[array.of.tables]]`. Errors are `TomlException` with 1-based line/column for syntax and semantic problems (duplicate keys, redefined tables, extending inline tables, array/table conflicts). Values are a sealed interface; `toString` gives a canonical JSON-like form.
- **Language:** java
- **Tags:** parser, config, toml, recursive-descent, sealed-interface, error-positions, table-kinds
- **Source round:** practice/java/20261117T1956Z
- **Reuse notes:** table state (implicit/explicit/dotted/inline) is kept in an identity map inside the parser so the public `Table` stays immutable; nesting depth is capped at 256 so hostile input yields a `TomlException`, not a `StackOverflowError`. Unsupported: multi-line strings, dates, `inf`/`nan`, octal/binary ints.
- **Tests:** `mvn -q -B test` (70 tests incl. seeded fuzz, model-vs-parse round trip, time-bounded 40k-line document).
