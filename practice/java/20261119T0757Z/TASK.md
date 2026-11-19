# Task: in-process partitioned log broker with consumer groups (`ae.broker`)

Level 20 capstone (topic: capstone systems; Kafka-style broker). Standard library only.

**Overlap check vs `benchmark/java/`:** j24-queue is a blocking queue, j29-kvlog is a crash-safe key-value file log, j13/j17/j21 are unrelated. None has partitions, consumer groups, generations or committed offsets. No existing skill covers it (`transactional-outbox` relays per-key but has no groups).

## Design
- `Broker(LongSupplier clockMillis, long sessionTimeoutMs)`; single lock, all methods synchronized, no threads.
- Topics have a fixed partition count. Each partition is an append-only log with offsets starting at 0. `logStart` is the first retained offset, `logEnd` the next offset to be written. `retain(topic, p, maxRecords)` drops the oldest records so at most `maxRecords` remain (offsets are never reused; `logStart` rises).
- `produce(topic, key, value)`: key non-null -> partition `Math.floorMod(key.hashCode(), partitions)`; null key -> per-topic round robin starting at partition 0. Returns `Metadata(partition, offset)`. Timestamp = clock.
- Groups: `join(group, member, topics)` returns `Membership(generation, assignment)`. Assignment is a sorted `List<TopicPartition>`. Joining a new member, or an existing one with a different subscription, bumps the generation (starting at 1) and rebalances; re-joining with an identical subscription is a no-op returning the current membership. All topics must exist (`IllegalArgumentException`).
- Rebalance: per topic (sorted), partitions ascending, the members subscribed to that topic sorted by id, partition `i` goes to `members[i % n]`. After every rebalance every group fetch position resets to the committed offset (or `logStart` if nothing committed): at-least-once delivery.
- `leave` and session expiry (`now - lastSeen > sessionTimeoutMs`, checked lazily at the start of every group call) remove the member and rebalance. An expired or unknown member's calls throw `UnknownMemberException`. `heartbeat`, `poll`, `commit` and `join` refresh `lastSeen`; `heartbeat` returns the current `Membership`.
- `poll(group, member, generation, max)`: a generation different from the group's current one throws `StaleGenerationException`. Returns up to `max` messages (`max >= 1`) from the member's partitions in assignment order, starting at each partition's position; if the position is below `logStart` it jumps to `logStart`. Advances positions.
- `commit(group, member, generation, tp, nextOffset)`: stale generation -> `StaleGenerationException`; partition not assigned to the member -> `IllegalStateException`; `nextOffset` outside `[0, position]` -> `IllegalArgumentException`. Stored per (group, tp), survives rebalances and members leaving.
- `committed(group, tp)` returns -1 when none. `lag(group)` = sum over all partitions of subscribed topics of `logEnd - max(committed or logStart, logStart)`.
- `assignments(group)` returns a read-only `Map<String, List<TopicPartition>>` (member -> assignment, members sorted) without refreshing or sweeping anything.
- A failing call changes nothing.

## Success criteria
`mvn -q -B clean test` in this folder passes; tests include a seeded randomized simulation (producers, joins, leaves, crashes via clock advance, polls, commits) with an oracle checking: no committed offset ever passes a delivered message, every message is delivered at least once to the group once everything is drained and committed, and each partition has at most one owner.
