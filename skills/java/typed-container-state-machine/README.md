# typed-container-state-machine

- **Description:** Typesafe heterogeneous container (`TypedKey<T>` record + `TypedMap`, with primitive boxing via `Class.cast`) and a generic `StateMachine<S extends Enum<S>>` backed by `EnumMap`/`EnumSet` with defensive copies, recorded history and failure-leaves-state-unchanged.
- **Language:** java (21)
- **Tags:** generics, type token, records, enums, EnumMap, EnumSet, state machine
- **Source round:** 20261114T0500Z
- Tests: `mvn -q -B test` (18 tests).
