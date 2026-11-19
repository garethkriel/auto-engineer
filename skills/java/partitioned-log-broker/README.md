# partitioned-log-broker

- **Description:** In-process Kafka-style broker: partitioned append-only logs with retention, keyed/round-robin produce, consumer groups with generations, deterministic range-free round-robin rebalance, session expiry via injected clock, at-least-once delivery (positions reset to committed offsets on rebalance), generation-fenced poll/commit, lag.
- **Language:** java
- **Tags:** broker, consumer-group, offsets, rebalance, generation-fencing, at-least-once, injectable-clock
- **Source round:** practice/java/20261119T0757Z
- **Test idea worth reusing:** seeded random simulation (produce/join/leave/clock jumps/poll/commit) with a read-only `assignments()` view so invariant checks never refresh sessions; final drain proves nothing below a position is ever lost. Mutants killed: no position reset on rebalance, no generation check on commit, never evicting, `>=` instead of `>` expiry.
