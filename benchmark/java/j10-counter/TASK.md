# j10-counter: Concurrent word count

Java 21, standard library only. Put your solution under `bench-solutions/java/j10-counter/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.counter;
public final class ConcurrentWordCounter {
    public static Map<String, Long> count(List<String> lines, int threads);
}
```

- Words are the non-empty pieces of each line split on whitespace (regex `\s+`), lower-cased with `Locale.ROOT`.
- Split the work across `threads` worker threads. The result must always equal a sequential count, however many times it runs.
- Don't leave threads running after the method returns.
- `lines == null` or `threads < 1` throws `IllegalArgumentException`.
