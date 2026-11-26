# Task: `ae.schema` — a JSON Schema (2020-12 subset) validator over plain Java values

Level 20 capstone. Overlap check: `benchmark/java/` names j01-roman .. j35-minisql were listed and read; none is a schema validator (j14-json is a JSON text parser; this task takes already-parsed values and never parses JSON text).

## Data model
Instances and schemas are plain Java trees: `null`, `Boolean`, `String`, numbers (`Byte/Short/Integer/Long/BigInteger/BigDecimal/Float/Double`, finite only), `List<?>`, `Map<String,?>`. Anything else in an *instance* -> `IllegalArgumentException`; anything else in a *schema* -> `SchemaException`.

## API
- `Validator.compile(Object schema)` -> `Validator`; throws `SchemaException` (unchecked) for a malformed schema. The schema is deep-copied (later mutation by the caller has no effect).
- `Validator.validate(Object instance)` -> `Result` with `valid()` and `violations()` (`List<Violation>`); never mutates the instance.
- `Violation(String instancePointer, String schemaPointer, String keyword, String message)`; pointers are RFC 6901 (`""` = root, `~0`/`~1` escaping).

## Supported keywords
Schemas may be booleans. `$ref` (only `#` and `#/json/pointer`, with `~0 ~1` and `%XX` decoding; siblings of `$ref` still apply), `$defs`, `type` (name or array; `integer` = number with zero fractional part, so `1.0` is an integer), `enum`, `const` (JSON equality: numbers by value, objects unordered, arrays ordered, `true != 1`), `allOf anyOf oneOf not if/then/else`, `minimum maximum exclusiveMinimum exclusiveMaximum multipleOf` (exact `BigDecimal` arithmetic on the decimal representation), `minLength maxLength` (code points), `pattern` (`java.util.regex` `find`), `prefixItems items contains minContains maxContains minItems maxItems uniqueItems`, `properties patternProperties additionalProperties propertyNames required minProperties maxProperties dependentRequired dependentSchemas`, `unevaluatedProperties unevaluatedItems` (annotations only from *valid* subschemas; `oneOf` contributes only when exactly one branch matched; `if` contributes only when it matched). Unknown keywords are ignored.

## Output rules
- Type-specific keywords apply only to instances of that type.
- Keyword order: `$ref type enum const allOf anyOf oneOf not if/then/else`, numeric, string, array, object keywords, then `unevaluatedItems`, `unevaluatedProperties`.
- Object keys of an instance are visited in ascending `String` order, so output is deterministic.
- `anyOf`/`oneOf`/`not` report one violation on the instance (no branch details); `if` failures are never reported.
- `schemaPointer` follows the dynamic path, including `/$ref` hops (e.g. `/properties/a/$ref/minimum`).
- Re-entering the same schema node at the same instance location (a `$ref` loop) -> `SchemaException`.

## Success criteria
Tests-first suite passes with `mvn -q -B test`: table-driven keyword cases, exact pointers/order, shape errors, instance errors, metamorphic randomized checks (`allOf[s,s] == s`, `not not s == s`, ...), plus a hand-run mutation pass.
