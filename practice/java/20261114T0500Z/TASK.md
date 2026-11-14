# Task 20261114T0500Z (level 5: generics, interfaces, enums, records)
Package `ae`. Overlap check vs benchmark/java (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex): none share a core algorithm (type-safe heterogeneous container + enum state machine).

1. `TypedKey<T>` record(String name, Class<T> type) with `of`; rejects null/blank name.
2. `TypedMap`: `<T> put(TypedKey<T>, T)` (null value rejected, returns this), `<T> Optional<T> get(TypedKey<T>)`, `size()`. Keys with same name but different type are distinct. Primitive-class keys (int.class) must work via boxing.
3. `StateMachine<S extends Enum<S>>`: constructed from `Class<S>`, initial state, `Map<S, Set<S>>` allowed transitions (defensively copied, EnumMap). `current()`, `canMove(S)`, `move(S)` (IllegalStateException on illegal, state unchanged), `history()` unmodifiable `List<Transition<S>>`, record `Transition<S>(S from, S to)`, `isTerminal()` (no outgoing).
4. `OrderState` enum (NEW, PAID, SHIPPED, DELIVERED, CANCELLED) with `static StateMachine<OrderState> newMachine()` and `parse(String)` returning Optional (case-insensitive, Locale.ROOT, trimmed).
Success: `mvn -q -B test` passes.
