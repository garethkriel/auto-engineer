# model-checker

- **Description:** explicit-state model checker (`ae.mc`): BFS safety checking with shortest counterexamples and deadlock detection, "eventually" liveness via iterative Tarjan SCCs returning a lasso (prefix + cycle), bounded exploration, plus example models (Peterson mutex, unsafe mutex, dining philosophers, water jugs).
- **Language:** java
- **Tags:** model-checking, bfs, tarjan, scc, liveness, counterexample, state-space
- **Source round:** practice/java/20261119T2056Z
- **Gotchas:** judge each state (invariants + deadlock) when it is *discovered*, not when dequeued, or a shallower deadlock can lose to a deeper violation with several initial states. Test against brute-force oracles (distance relaxation; greatest fixpoint of EG) on random graphs.
