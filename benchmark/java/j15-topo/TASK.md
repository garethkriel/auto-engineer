# j15-topo: Deterministic dependency order

Java 21, standard library only. Put your solution under `bench-solutions/java/j15-topo/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.topo;
public final class CycleException extends RuntimeException {
    public CycleException(java.util.List<String> cycle);
    public java.util.List<String> cycle();
}
public final class DependencyResolver {
    public static java.util.List<String> order(java.util.Map<String, java.util.Set<String>> dependsOn);
}
```

- **The input:** `dependsOn.get(x)` is the set of nodes that `x` depends on, which must come before `x`. The nodes are every key plus every name that appears in any set.
- **The order:** return every node exactly once, each after all its dependencies. Of all such orders, return the **lexicographically smallest** one, comparing names with `String.compareTo`. For example, `{a: [b], c: []}` gives `[b, a, c]`.
- **Cycles:** if there's a cycle, throw `CycleException`. Its `cycle()` is a list `[n1, n2, …, nk, n1]` where each node depends on the next one. A node that depends on itself gives `[a, a]`. Any cycle in the graph is acceptable.
- **Performance and input:** it must handle a chain of 20,000 nodes within a few seconds, and must not modify the input.
- **Errors:** a null map, a null dependency set, or a null node name throws `IllegalArgumentException`.
