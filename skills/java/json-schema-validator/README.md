# json-schema-validator

- **Description:** JSON Schema 2020-12 subset validator over plain Java values (`ae.schema.Validator`): `$ref` to `#/pointer`, `$defs`, combinators, if/then/else, exact `BigDecimal` numerics (`multipleOf` is decimal-exact), code-point lengths, `contains`/`minContains`/`maxContains`, `unevaluatedProperties`/`unevaluatedItems` with annotation tracking, `$ref` loop detection, deterministic violations with instance and dynamic schema pointers.
- **Language:** java
- **Tags:** json-schema, validation, ref, annotations, bigdecimal, metamorphic-tests
- **Source round:** `practice/java/20261126T1157Z`

Key design points: each schema object evaluation returns an `Ann` (evaluated properties/items); in-place applicators merge a child's annotations only when the child is valid. Schemas are deep-copied and shape-checked at compile time (`SchemaException`); `$ref` targets are resolved and checked then. Run `mvn -q -B test` (104 tests, including seeded metamorphic checks).
