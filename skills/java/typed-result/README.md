# typed-result

- **Description:** Sealed `Result<T,E>` (records Ok/Err) with map/flatMap/mapError/sequence, wildcard-correct generic helpers (`max`, `copyInto`, `keyOfMax`), and an enum whose `parse` returns a `Result` instead of throwing.
- **Language:** java (21)
- **Tags:** generics, wildcards, sealed interface, records, enum, pattern-matching switch, PECS
- **Source round:** 20261114T0356Z
- Tests: `mvn -q -B test` (20 tests).
