# j08-graph: Shortest path

Java 21, standard library only. Put your solution under `bench-solutions/java/j08-graph/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.graph;
public final class Graph {
    public static List<String> shortestPath(Map<String, Map<String, Integer>> edges, String from, String to);
}
```

- `edges.get(u).get(v)` is the weight of the directed edge u → v. Weights are >= 0. A node missing from `edges` has no outgoing edges.
- Return the nodes of a minimum-total-weight path from `from` to `to`, including both ends.
  - If `from.equals(to)`, return `[from]`.
  - If `to` is unreachable, return an empty list.
- It must handle graphs of about 10,000 nodes within a few seconds.
- A null argument or a negative weight throws `IllegalArgumentException`.
