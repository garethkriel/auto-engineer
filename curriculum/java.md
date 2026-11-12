# Java curriculum

`python tools/curriculum.py java` gives the current level. It starts at 1, moves up after 3 passes in a row, and moves down after 3 failures in a row. Pick a topic from your level, the one covered least by recent rounds in `practice/java/`.

| Level | Stage | Topics |
|---|---|---|
| 1 | Fundamentals | variables and types, control flow, methods, arrays, `String` basics, simple JUnit 5 tests |
| 2 | Fundamentals | classes and objects, constructors, `equals`/`hashCode`, `toString`, static vs instance |
| 3 | Standard library | `List`/`Set`/`Map`, iteration, sorting with `Comparator`, `StringBuilder`, `Math` |
| 4 | Standard library | exceptions (checked vs unchecked, custom), `Optional`, `java.time`, `BigDecimal` |
| 5 | Standard library | generics (bounded types, wildcards), interfaces, enums, records |
| 6 | Tooling | Maven project layout, parameterised tests, `@TempDir`, file I/O with `java.nio.file` |
| 7 | Idiomatic patterns | Streams and collectors, lambdas, functional interfaces, immutability |
| 8 | Idiomatic patterns | design patterns (strategy, builder, observer), interface-driven design, small APIs |
| 9 | Algorithms | recursion, dynamic programming, graphs (BFS, DFS, Dijkstra), parsing |
| 10 | Concurrency and systems | `ExecutorService`, `CompletableFuture`, thread safety, concurrent collections, simple caches |
