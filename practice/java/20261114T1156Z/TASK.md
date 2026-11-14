# Task 20261114T1156Z (level 7: lambdas, functional interfaces, immutability)

Build package `fn` with two small functional utilities:

1. `Validator<T>` (`@FunctionalInterface`, `List<String> validate(T)`): composable, error-accumulating validation.
   - `Validator.of(Predicate<? super T>, String message)`; `and(other)` concatenates errors of both (no short-circuit); `allOf(...)`.
   - `field(name, extractor, validator)` prefixes each error with `name: `; `each(validator)` for `Iterable` prefixes `[i]: `.
   - `isValid(t)`, `requireValid(t)` throws unchecked `ValidationException` carrying an immutable error list.
   - Returned lists are unmodifiable; null args rejected early.
2. `Lazy<T>`: thread-safe memoising supplier. `get()` computes once; a throwing supplier is not cached (retried next call); `map(fn)` gives a new Lazy; `isEvaluated()`.

Assumptions: a null supplier result is allowed and memoised. Overlap check vs benchmark/java (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex): none.

Success: `mvn -q -B test` passes.
