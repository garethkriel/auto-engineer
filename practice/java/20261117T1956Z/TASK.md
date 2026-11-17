# 20261117T1956Z — `tconf`: a TOML-subset parser with positioned errors

Level 13 (language tools). Topic: tokenising/recursive-descent parsing, sealed-interface values, error messages with line and column.

**Overlap check** against `benchmark/java/` names: j04-csv (flat rows), j07-expr, j14-json, j20-cron, j22-regex, j28-script, j35-minisql. A table-structured config language with headers, dotted keys and array-of-tables is none of them (nor the earlier `tpl` template engine round).

## Assumptions (nobody to ask)
Standard library only. Package `ae.tconf`. Multi-line strings (`"""`), dates, `inf`/`nan` and octal/binary ints are explicitly unsupported and must produce a `TomlException`, not a crash.

## API
- `Toml.parse(String) -> Table`, throws unchecked `TomlException` (`line()`, `column()` 1-based; message `line L, column C: text`).
- `sealed interface Value permits Str, Int, Flt, Bool, Arr, Table`.
- `Table`: `Optional<Value> get(String... path)`, `List<String> keys()` (insertion order), `size()`, `toString()` in a JSON-like canonical form: `{a=1, b="x", c=[1, 2], d={e=true}}` (keys quoted only if not `[A-Za-z0-9_-]+`).
- `Arr.items()`, `Str.value()`, `Int.value()` (long), `Flt.value()`, `Bool.value()`.

## Grammar supported
- `#` comments, blank lines, `\n` and `\r\n` newlines.
- `key = value`; keys bare (`A-Za-z0-9_-`), `"quoted"` or `'literal'`, dotted (`a.b."c d" = 1`) with spaces around dots.
- Strings: basic `"..."` with escapes `\n \t \r \b \f \" \\ \uXXXX`; literal `'...'` without escapes. No raw newline in either.
- Integers (`+1`, `-2`, `1_000`, `0xFF`, no leading zeros), floats (`1.5`, `1e3`, `-2.5e-3`, `1_0.0_1`), booleans.
- Arrays (mixed types allowed, trailing comma allowed, newlines and comments allowed inside); inline tables `{a = 1, b.c = 2}` (single line, no trailing comma).
- `[a.b]` tables and `[[a.b]]` arrays of tables (headers may contain spaces around dots).

## Semantic errors (all positioned at the offending key/header token start)
duplicate key; redefining a `[table]`; `[a]` after dotted keys defined `a`; extending an inline table; `[[x]]` over a static array or a table; `[x]` over an array of tables; using a non-table as a path prefix.
`[a.b]` followed later by `[a]` is **allowed** (`a` was implicit). Extending the last element of an array of tables via `[a.sub]` is allowed.

## Syntax errors
unterminated string (position of opening quote), unterminated array / inline table (position of opening bracket), invalid escape (position of the backslash), trailing comma in inline table, missing `=`, empty value, bad number, integer/float out of range, garbage after a value (`expected end of line`).

## Success criteria
`mvn -q -B clean test` green; tests cover each grammar item with exact `toString` expectations, every error kind with exact line/column, a seeded fuzz (only `TomlException` may escape), a model-vs-parse round trip, and a time-bounded 20k-key document.
