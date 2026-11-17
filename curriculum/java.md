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
| 11 | Advanced concurrency | `ReentrantLock`, `ReadWriteLock`, `StampedLock`, `Condition`, atomics and CAS, lock-free structures, the Java memory model (`volatile`, happens-before), deterministic tests for races |
| 12 | Performance | complexity analysis, choosing data structures, primitive arrays vs boxing, bit manipulation, memoisation, time-bounded tests on large inputs |
| 13 | Language tools | tokenisers, recursive-descent parsers, ASTs as sealed interfaces with pattern matching, evaluators, error messages with line and column |
| 14 | Storage | binary formats with `ByteBuffer` and `DataOutputStream`, checksums (`CRC32`), append-only logs, crash-safe writes (temp file then atomic move), small key-value stores |
| 15 | Networking | `java.net.http.HttpClient`, `com.sun.net.httpserver.HttpServer`, sockets, timeouts, retries with backoff, tests against an in-process local server |
| 16 | Advanced data structures | tries, heaps, union-find, interval and segment trees, skip lists, LFU caches, B-tree style indexes |
| 17 | Larger designs | several packages with clear boundaries, dependency inversion, ports and adapters, event-driven designs, explicit state machines; write the design in `TASK.md` first |
| 18 | Refactoring | the task supplies its own messy but working starting code; add characterisation tests first, then refactor (extract class, replace conditionals with polymorphism) with behaviour unchanged |
| 19 | Distributed ideas, simulated in-process | consistent hashing, vector clocks, leader election and log replication over a simulated unreliable network, idempotent requests, seeded deterministic simulation tests |
| 20 | Capstone systems | small end-to-end systems that combine earlier levels, such as a mini database with a query language, a persistent job scheduler with retries, or a rate-limited HTTP service; design first, then build test-first |

Levels 11–20 use the standard library only, like every level: the template `pom.xml` has JUnit 5 and nothing else. Tasks get larger from level 17, but each must still fit one practice round.
