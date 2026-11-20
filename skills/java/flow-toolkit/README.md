# flow-toolkit

- **Description:** Network-flow toolkit in package `ae.flow`: iterative Dinic max-flow with min cut, successive-shortest-path min-cost flow (negative costs, negative-cycle rejection, optimality certificate), Hopcroft–Karp matching with König vertex cover, Hungarian assignment, and a text-spec shift planner (max fill, then min cost).
- **Language:** java
- **Tags:** max-flow, min-cut, min-cost-flow, bipartite-matching, hungarian, assignment, planning
- **Source round:** 20261120T0456Z (`practice/java/20261120T0456Z`)
- **Tests:** 40 JUnit tests: CLRS literal, brute-force enumeration, Kuhn/permutation/subset oracles, cross-engine Hungarian vs min-cost flow, 200k-node chain stack check.

Gotchas: `maxFlow` returns only the additional flow; `minCostFlow` needs a zero-flow network; capacities <= Long.MAX/4, |cost| <= 1e12.
