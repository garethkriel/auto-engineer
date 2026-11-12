# Language Profile: Java

## Toolchain (cloud VM)

- OpenJDK 21, Maven and Gradle preinstalled. This repo uses **Maven**.
- Every project folder (practice round, skill, benchmark solution) has its own `pom.xml` copied from `templates/java/pom.xml`.

## Commands (run inside the project folder)

| Purpose | Command |
|---|---|
| Build | `mvn -q -B -DskipTests compile` |
| Test | `mvn -q -B test` |
| Lint | the compile step, which runs `-Xlint:all` (template pom); treat warnings as defects |
| Format | no formatter installed. Use 4-space indents and standard Java naming |
| Run one test class | `mvn -q -B test -Dtest=ClassNameTest` |

Test reports are written to `target/surefire-reports/`.

## Idioms

- Prefer immutable data. Use `record` for value types and `List.of`/`Map.of` for fixed collections.
- Use `Optional` for a missing return value, never for fields or parameters.
- Use the Streams API for transformations, and plain loops when they read more clearly.
- Use `BigDecimal` for money, never `double`.
- Validate arguments early with `Objects.requireNonNull` and `IllegalArgumentException`.
- Use try-with-resources for anything `AutoCloseable`.
- For concurrency, prefer `ExecutorService` and `java.util.concurrent` types over raw threads.

## Testing (JUnit 5)

- Use `org.junit.jupiter.api.Test` and `Assertions.*`, plus `assertThrows` for exceptions.
- Use `@TempDir` for file tests, and `@ParameterizedTest` with `@CsvSource` for tables of cases.
- Each test checks one behaviour, and its name says what it checks.

## Common pitfalls

- Comparing strings or boxed numbers with `==` instead of `.equals`.
- Integer overflow in sums and midpoints: use `long`, or `Math.addExact`.
- Mutating a collection while iterating over it.
- `HashMap` iteration order is not stable. Don't assert on it, so sort or use `LinkedHashMap`.
- Leaking resources by forgetting try-with-resources.
- Leaving an `ExecutorService` unshut, or leaving shared mutable state unsynchronised.
- Character case or locale issues: use `toLowerCase(Locale.ROOT)`.
