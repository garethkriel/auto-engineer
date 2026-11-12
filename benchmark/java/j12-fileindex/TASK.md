# j12-fileindex: Word index of text files

Java 21, standard library only. Put your solution under `bench-solutions/java/j12-fileindex/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.fileindex;
public final class FileIndexer {
    public static Map<String, Set<Path>> index(Path dir) throws IOException;
}
```

- Walk `dir` recursively and read every regular file whose name ends in `.txt`, as UTF-8. Ignore all other files.
- A word is a maximal run of Unicode letters or digits, lower-cased with `Locale.ROOT`. Map each word to the set of files containing it.
- Each path is the file's path as returned by `Files.walk(dir)`.
- A null `dir`, a missing path, or a path that isn't a directory throws `IllegalArgumentException`.
