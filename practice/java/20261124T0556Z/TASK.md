# Level 20 capstone: `ae.broker`, an in-process MQTT-style pub/sub broker core

Overlap check: `benchmark/java/` j01-j35 contains no topic-wildcard matching, subscription trie, retained message or QoS redelivery task
(nearest: j24-queue, j13-ratelimit, j34-vclock share no core algorithm). Existing skills `typed-event-bus` (class-keyed) and
`partitioned-log-broker` (offset logs) have no wildcards, sessions or acknowledgements.

## Design
- `Topics`: `validateTopic`, `validateFilter`, reference matcher `matches(filter, topic)`.
  Levels are split on `/` (empty levels are real levels). `+` matches exactly one level and must fill a whole level;
  `#` matches the rest (including the parent: `a/#` matches `a`) and must be the whole last level.
  Topics whose first level starts with `$` never match a filter whose first level is `+` or `#`.
- `SubscriptionTrie<V>`: filter -> (key, value) entries; `add`, `remove` (prunes empty nodes), `match(topic)`; must agree with `Topics.matches`.
- `Broker`: sessions, subscriptions, retained messages, QoS 0/1, will messages, injected clock (`LongSupplier`), pull-style delivery.
  - `connect(id, clean, will)` -> `true` if a stored session was resumed. Connecting an id that is already connected
    drops the old connection abnormally (its will is published). Clean connect discards any stored session.
  - `disconnect(id)` graceful: will discarded. `drop(id)` abnormal: will published. Clean session is erased; persistent
    one is kept offline with subscriptions, in-flight and pending QoS 1 messages; QoS 0 is not stored offline.
  - `subscribe(id, filter, qos)` (replaces an existing subscription of the same filter) sends matching retained messages
    (retain flag true, qos = min). `unsubscribe`.
  - `publish(topic, payload, qos, retain)`: qos 0 or 1 only. Retained: empty payload clears the retained message.
    Each client gets one copy: qos = min(publish qos, highest qos among its matching subscriptions); forwarded copies have retain=false.
  - QoS 1: packet ids 1..65535 (cycling, skipping ids in use), at most `maxInflight` unacked; extra go to a pending queue
    capped at `maxQueued` (oldest dropped, counted). `ack(id, packetId)` frees a slot and sends pending ones.
    `tick()` resends unacked messages older than `retryMs` with dup=true, and expires offline sessions after `sessionExpiryMs`.
    On resume, in-flight messages are resent first (dup=true, same ids), then pending ones, preserving publish order.
  - `drain(id)` returns and clears the client's outgoing deliveries.

## Success criteria
`mvn -q -B clean test` passes: literal tests for every rule above, a trie-vs-matcher differential test on random topics/filters,
a seeded random broker simulation with an invariant checker and a delivery oracle, and a recorded mutation check.
