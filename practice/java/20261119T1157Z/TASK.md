# Level 20 capstone: `ae.coord` – a ZooKeeper-style coordination kernel

Overlap check against `benchmark/java/` (j01..j35): none is a hierarchical namespace with sessions/ephemerals/watches/locks (nearest: j13-ratelimit, j34-vclock, j29-kvlog – unrelated algorithms). No overlap.

## Design
In-process, single JVM, deterministic (injected `Clock`), standard library only.

* **Namespace**: tree of nodes addressed by absolute paths (`/a/b`). Root `/` always exists, is persistent and cannot be deleted. Valid path: starts with `/`, no trailing `/` (except root), no empty segment, no `.`/`..` segment, no NUL char; else `BAD_PATH`.
* **Node**: `data` (String), `Stat(czxid, mzxid, version, cversion, ephemeralOwner, numChildren)`. `version` counts setData calls; `cversion` counts child creations+deletions (monotonic, never reused). `ephemeralOwner` = session id, or 0 for persistent.
* **zxid**: global counter, +1 per committed transaction (a single op, a `multi`, or a session expiry that removes ≥1 ephemeral). Failed transactions do not consume a zxid.
* **Create modes**: PERSISTENT, EPHEMERAL, PERSISTENT_SEQUENTIAL, EPHEMERAL_SEQUENTIAL. Sequential appends the parent's current `cversion` zero-padded to 10 digits. Parent must exist (`NO_NODE`); ephemeral parents are forbidden (`EPHEMERAL_PARENT`); duplicate → `NODE_EXISTS`.
* **delete(path, expectedVersion)**: `-1` = any version. `NO_NODE`, `BAD_VERSION`, `NOT_EMPTY`. **setData** likewise.
* **Sessions**: `connect(timeoutMs)` (>0) returns an id (1,2,3..). Only `heartbeat` refreshes a session. A session expires when `now - lastHeartbeat >= timeout`. Every public call first expires all due sessions (in deadline, id order), so no explicit tick is needed (`tick()` exists to force it). Expiry/`close` deletes the session's ephemerals in one transaction and drops its registered watches. Using a dead/unknown session → `SESSION_EXPIRED`.
* **Watches** (one-shot, registered atomically with a read): `getData`/`exists` register a *data* watch (also on missing nodes); `getChildren` registers a *child* watch. Events: `NODE_CREATED`, `NODE_DELETED`, `NODE_DATA_CHANGED`, `NODE_CHILDREN_CHANGED`. Created→data watches of path; Deleted→data+child watches of path and child watches of parent (as CHILDREN_CHANGED); DataChanged→data watches; child create/delete→child watches of parent. Events are dispatched only after the transaction commits, in op order, **outside the service lock** (so watchers may call back in).
* **multi(session, ops)**: atomic list of Create/Delete/SetData/Check. On any failure nothing changes (no zxid, no cversion, no events) and a `CoordException` with `opIndex()` of the failing op is thrown.
* **Recipe `DistributedLock`**: ephemeral-sequential queue under a lock path; waiter watches only its predecessor (no herd); the sequence number is the monotonic fencing token. Handle states WAITING, GRANTED, RELEASED, LOST (session died while waiting/holding).

## Success criteria
`mvn -q -B clean test` green: unit tests for every rule above, a seeded randomized differential test against a naive model with an invariant checker run after every step, and lock fairness/fencing tests.
