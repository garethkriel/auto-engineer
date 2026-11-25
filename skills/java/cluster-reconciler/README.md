# cluster-reconciler

- **Description:** Deterministic model of a Kubernetes-like control loop: `Cluster` with nodes, `DeploymentSpec`s, a kubelet phase, a deployment controller doing rolling updates bounded by `maxSurge`/`maxUnavailable`, a most-free-cpu scheduler and node failure/recovery. Includes invariant tests (surge, availability, capacity) and seeded fault simulations.
- **Language:** java
- **Tags:** reconcile-loop, rolling-update, scheduler, simulation, invariants, capstone
- **Source round:** practice 20261125T2057Z (level 20)
- **Test:** `mvn -q -B test` (24 tests)
