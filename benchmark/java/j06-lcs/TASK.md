# j06-lcs: Longest common subsequence

Java 21, standard library only. Put your solution under `bench-solutions/java/j06-lcs/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.lcs;
public final class Lcs {
    public static int length(String a, String b);
    public static String sequence(String a, String b);
}
```

- `length` returns the length of a longest common subsequence of `a` and `b`.
- `sequence` returns one longest common subsequence. If several exist, any one will do.
- It must handle strings of a few thousand characters each within a few seconds.
- A null argument throws `IllegalArgumentException`.
