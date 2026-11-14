# Task 20261114T0556Z

Level 5 (generics, interfaces, enums, records), observer-style typed event bus.

Implement package `ae.bus`:
- `enum Priority { HIGH, NORMAL, LOW }` — delivery order (HIGH first).
- `record DeliveryReport(int delivered, List<Throwable> failures)` — immutable (defensive copy), `ok()`.
- `final class Subscription` with `cancel()` (idempotent) and `isActive()`.
- `final class EventBus`:
  - `<E> Subscription subscribe(Class<E> type, Consumer<? super E> handler)` (NORMAL priority) and overload with `Priority`.
  - `DeliveryReport publish(Object event)`: delivers to every active handler whose type `isAssignableFrom` the event class, ordered by priority then subscription order. A throwing handler does not stop others; its exception goes into failures. Handlers subscribed/cancelled during a publish affect only later publishes (snapshot).
  - Null arguments -> NullPointerException. Primitive class tokens (`int.class`) match boxed events.

Success: `mvn -q -B test` passes. Assumptions: single-threaded use (not thread-safe).
Benchmark overlap check: j01-roman..j12-fileindex (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex) — none is an event bus/observer; no overlap.
