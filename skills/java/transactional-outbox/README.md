# transactional-outbox

- **Description:** Transactional-outbox template with ports and adapters: `Database.inTransaction` port (all-or-nothing, nested/stale `Tx` rejected), in-memory adapter on a working copy, booking service that writes the business change and the outbox message in one transaction, and an `OutboxRelay` giving at-least-once delivery with per-key ordering, exponential backoff with cap, dead-lettering, interrupt handling, plus a consumer-side `IdempotentReceiver`.
- **Language:** java
- **Tags:** outbox, ports-and-adapters, transactions, retry-backoff, dead-letter, idempotency, at-least-once
- **Source round:** practice/java/20261118T0956Z
- **Tests:** 28, `mvn -q -B test`; includes a 40-seed randomised failure/ack-loss simulation against an oracle of published payloads and a concurrent no-overbooking test.
