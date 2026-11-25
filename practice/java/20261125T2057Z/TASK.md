# Task: mini cluster reconciler (level 20 capstone)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). A declarative deployment reconciler with a scheduler and rolling updates matches none of them.

## Goal
Package `recon`: a deterministic, in-process model of a Kubernetes-like control loop. A user declares `DeploymentSpec`s; a `Cluster` repeatedly reconciles actual pods towards the declared state.

## Types
- `record DeploymentSpec(String name, int replicas, String version, int cpu, int maxSurge, int maxUnavailable)`: compact constructor throws `IllegalArgumentException` for blank name/version, `replicas < 0`, `cpu <= 0`, negative surge/unavailable, or `maxSurge == 0 && maxUnavailable == 0` (rollouts could not progress).
- `enum Phase { PENDING, RUNNING }`
- `record Pod(int id, String deployment, String version, int cpu, Phase phase, String node)`: `node` is `null` while unbound.
- `Cluster`:
  - `addNode(String name, int cpu)` (duplicate name or cpu <= 0: IAE), `failNode(String)` (node goes down, every pod bound to it is deleted at once; unknown name: IAE), `recoverNode(String)` (up again, empty).
  - `apply(DeploymentSpec)` creates or replaces the spec of that name. `delete(String name)` removes the spec and all its pods at once.
  - `tick()` runs one reconcile round, phases in this exact order:
    1. **Kubelet**: every PENDING pod that is bound to a node becomes RUNNING.
    2. **Deployment controllers**, deployments in name order, each as below.
    3. **Scheduler**: unbound pods in id order; each goes to the up node with the most free cpu that fits it (ties: smaller node name). Free cpu = capacity minus cpu of pods bound there (PENDING or RUNNING). A pod that fits nowhere stays unbound.
  - `pods()` all pods in id order, `pods(String deployment)`, `running(String deployment)` count, `freeCpu(String node)`.
  - `settled()` true iff for every deployment: exactly `replicas` pods, all of the current version and RUNNING, and no pods of deleted deployments exist.

## Deployment controller (one deployment, state at start of phase 2)
Let `new` = pods with the spec's version, `old` = the rest, `running` = RUNNING pods of the deployment (new and old), `total` = all pods.
1. *Scale-in of new*: if `|new| > replicas`, delete the surplus, unbound/PENDING first, then highest id first.
2. *Scale-up*: `n = min(replicas - |new|, replicas + maxSurge - total)`; create `max(0, n)` PENDING unbound new pods (ids from a global increasing counter, starting at 1).
3. *Scale-down of old*: delete every old pod that is not RUNNING; then `minAvail = max(0, replicas - maxUnavailable)`; delete up to `max(0, running - minAvail)` old RUNNING pods, lowest id first. (`running` is counted before step 2 creates pods, which are not running anyway.)
   Steps 2 and 3 use `total`/`running` from the start of the phase; deletions of step 1 are subtracted from `total`.

## Invariants to test
- Cluster capacity: bound cpu on a node never exceeds its capacity, after every tick.
- During a rollout from a settled state with no failures: `total <= replicas + maxSurge` and `running >= replicas - maxUnavailable` after every tick; it converges to `settled()` when capacity allows; if the new version never fits, availability stays at or above the minimum and the old pods stay.
- Randomised (seeded) simulations with node failure/recovery, spec changes, scale changes: capacity invariant after every tick; after faults stop and nodes recover, `settled()` within a bound.

## Assumptions
Pods are deleted without a grace period; startup costs exactly one tick; node failure deletes pods (the controller replaces them).
