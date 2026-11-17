# j18-diff: Minimal line diff

Java 21, standard library only. Put your solution under `bench-solutions/java/j18-diff/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.diff;
public final class LineDiff {
    public static java.util.List<String> diff(java.util.List<String> a, java.util.List<String> b);
}
```

- **The edit script:** return a script that turns `a` into `b`. Each entry is a one-character prefix followed by the line:
  - `" "` + line: the line is kept;
  - `"-"` + line: the line is deleted from `a`;
  - `"+"` + line: the line is inserted from `b`.
- **Correctness:** reading the kept and deleted lines in order gives exactly `a`, and reading the kept and inserted lines in order gives exactly `b`.
- **Minimality:** the number of `-` and `+` entries must be as small as possible, which is `|a| + |b| − 2·LCS(a, b)`.
- **Tie-breaking:** scan both lists from the start.
  - Whenever the current lines of `a` and `b` are equal, keep them.
  - When deleting the current `a` line and inserting the current `b` line are both optimal, delete first. So `diff([x], [y])` is `["-x", "+y"]`.
- **Lines:** they may be empty, and may themselves start with a space, `-` or `+`.
- **Performance:** it must handle two lists of 3,000 lines each within a few seconds.
- **Errors:** a null list throws `IllegalArgumentException`.
