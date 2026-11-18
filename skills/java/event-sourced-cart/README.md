# event-sourced-cart

- **Description:** Event-sourcing template with ports and adapters: sealed events, immutable aggregate (`decide*` returns events, `apply` folds), `EventStore` port with optimistic concurrency and all-or-nothing appends, in-memory adapter, command service, and a checkpointed idempotent read-model projection (`catchUp`/`rebuild`).
- **Language:** java
- **Tags:** event-sourcing, ports-and-adapters, projection, optimistic-concurrency, sealed-interface
- **Source round:** practice/java/20261118T0856Z
- **Tests:** 29, `mvn -q -B test`; includes randomised projection-vs-aggregate oracle and concurrent appender tests.
