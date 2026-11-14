# Task (level 5: generics, interfaces, enums, records)

Implement package `ae`:

- `sealed interface Result<T, E>` with records `Ok<T,E>(T value)` and `Err<T,E>(E error)`.
  Methods: `isOk()`, `map(Function<? super T, ? extends U>)`, `flatMap(Function<? super T, ? extends Result<U,E>>)`,
  `mapError(...)`, `orElse(T)`, `getOrElseGet(Function<? super E, ? extends T>)`, `toOptional()`.
  Static `of(Supplier)` capturing a RuntimeException as `Err<T, RuntimeException>`, and
  `sequence(List<Result<T,E>>)` -> `Result<List<T>,E>` (first error wins, result list immutable).
- `Generics` utility: `<T extends Comparable<? super T>> Optional<T> max(Collection<? extends T>)`,
  `<T> void copyInto(List<? super T> dst, List<? extends T> src)`,
  `double sum(Collection<? extends Number>)`, `<K, V extends Comparable<? super V>> Optional<K> keyOfMax(Map<K,V>)`.
- `enum Severity` (LOW, MEDIUM, HIGH, CRITICAL) with `weight()` and `parse(String)` returning `Result<Severity,String>`
  (case-insensitive, Locale.ROOT), and `atLeast(Severity)`.

Success: `mvn -q -B test` passes. Assumption: null values/errors are rejected with NPE.
Benchmark overlap check: no core algorithm shared with j01..j12 (types/generics API, not algorithms).
