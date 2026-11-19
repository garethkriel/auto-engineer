# Task: hierarchical lock manager with deadlock detection (level 20 capstone)

Package `ae.locks`. A deterministic, single-process, **non-blocking** multi-granularity lock manager
(the lock table of a database). Nobody sleeps: `acquire` says GRANTED, WAITING or ABORTED, and later
operations report which waiting transactions were woken. This makes every schedule reproducible.

Overlap check vs `benchmark/java/`: `j26-rwlock` is a blocking reader/writer lock on one object and
`j15-topo` is plain ordering; neither has lock modes, hierarchies, upgrades or deadlock victims. No overlap.

## Design

### `LockMode` (enum): `IS, IX, S, SIX, X`
* `compatibleWith(other)` – the standard matrix (symmetric):

| | IS | IX | S | SIX | X |
|---|---|---|---|---|---|
| IS | y | y | y | y | n |
| IX | y | y | n | n | n |
| S | y | n | y | n | n |
| SIX | y | n | n | n | n |
| X | n | n | n | n | n |

* `join(other)` – least upper bound in the lattice IS < {IX, S} < SIX < X, with join(IX,S)=SIX.
* `covers(other)` = `join(other) == this`. `intention()` = IS for IS/S, IX for IX/SIX/X.

### `LockManager` (all public methods `synchronized`; not blocking)
Resources are `/`-separated paths (`db/orders/row7`); the parent of `a/b/c` is `a/b`. A path with an empty
segment, a leading/trailing `/` or an empty/null string is `IllegalArgumentException`.

* `begin(long txn)` registers a transaction. Its **age** is its begin order (not its id). Reusing an id,
  even an ended one, is `IllegalArgumentException`.
* `Outcome acquire(txn, resource, mode)`:
  * Plan = intention lock `mode.intention()` on every ancestor, root first, then `mode` on the resource.
    A step needing a mode already covered by the held mode is a no-op (held X covers everything).
  * A step where the txn already holds a mode `h` that does not cover the request is an **upgrade** to
    `h.join(request)`; otherwise a fresh request.
  * Grant rule: granted iff compatible with every *other* holder, and (for a fresh request) the queue is
    empty. Upgrades ignore the queue and, when they must wait, are queued ahead of all fresh requests
    (behind earlier upgrades). Queues are strict FIFO: the head blocks everyone behind it.
  * When a step must wait the txn is BLOCKED; the remaining steps resume automatically when it is granted
    (and may block again). A BLOCKED txn may not `acquire`, `unlock` or `commit` (`IllegalStateException`).
  * Deadlock detection runs whenever something blocks. Wait-for edges of a waiting request: every other
    holder with an incompatible mode, plus every earlier queue entry with an incompatible target.
    While a cycle exists the **youngest** (greatest age) txn in it is aborted (all locks released, queue entry
    removed) and the grants this causes are processed. Cycle search order is by age so results are deterministic.
  * `Outcome.status`: `GRANTED` (txn ACTIVE on return, possibly because a victim was aborted),
    `WAITING` (txn BLOCKED), `ABORTED` (the requester itself was the victim).
* `Events commit(txn)` – must be ACTIVE; releases everything. `Events abort(txn)` – any live txn.
  `Events unlock(txn, resource)` – early release of one lock, only if the txn holds it, holds nothing
  below it, and is ACTIVE; afterwards the txn is *shrinking* and any `acquire` is `IllegalStateException` (2PL).
* `Events(List<Long> unblocked, List<Long> aborted)`: txns whose whole plan completed because of this
  operation (ascending order of wake-up), and deadlock victims. A txn woken at one step but blocked again
  at the next is not listed. `aborted` lists every victim, including the requester of `acquire` when it was
  chosen; `unblocked` never lists the requester of `acquire`.
* Queries: `state(txn)` (`ACTIVE, BLOCKED, COMMITTED, ABORTED`), `held(txn, resource)` (Optional),
  `locks(txn)` (sorted copy), `holders(resource)`, `queue(resource)` (waiting txn ids, queue order).
* Failed calls (bad arguments, wrong state) change nothing.
* Package-private `checkInvariants()` verifies: pairwise-compatible holders; holder maps agree with txn
  tables; every lock has covering intention locks on all ancestors; queue entries match BLOCKED txns
  one-to-one; no queue head is grantable (no missed wake-up); the wait-for graph is acyclic; ended txns hold
  nothing.

## Success criteria
`mvn -q -B clean test` passes: literal matrix/join tables plus lattice laws, FIFO, upgrade, hierarchy,
resumed multi-step plans, 2/3-party and upgrade deadlocks with victim choice, 2PL, validation and
no-side-effect tests, and seeded random simulations that run `checkInvariants()` after every operation and
compare an independent model of "who is BLOCKED" built only from reported outcomes/events.
