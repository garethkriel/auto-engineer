# mqtt-broker-core

- **Description:** in-process MQTT-style pub/sub broker core: topic/filter validation (`+`, `#`, `$` rule), pruning subscription trie, sessions (clean/persistent, takeover, expiry), retained messages, will messages, QoS 0/1 with packet ids, in-flight window, pending queue with drop-oldest, ack, timed redelivery with dup flag, resume-resend.
- **Language:** java (21, JUnit 5, standard library only)
- **Tags:** pubsub, mqtt, trie, wildcard, qos, retained, sessions, simulation, invariants, mutation-check
- **Source round:** `practice/java/20261124T0556Z`
- **Entry points:** `ae.broker.Broker` (injected `LongSupplier` clock, pull-style `drain`), `Topics`, `SubscriptionTrie<V>`, `Delivery`.
- **Tests:** literal rule tests, trie vs reference-matcher differential test, 60-seed random simulation with an invariant checker (`Broker.checkInvariants`) and an exactly-once-after-ack, in-order delivery oracle. A 16-mutant hand check killed 15; the survivor is equivalent.
