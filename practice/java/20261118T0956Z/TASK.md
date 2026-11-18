# Task (level 17, larger design): ticket booking with a transactional outbox, ports and adapters

Overlap check: `benchmark/java/` has j09-bank, j13-ratelimit, j19-allocate, j24-queue, j32-fsm, j33-pricing, j34-vclock.
This task is seat booking + a transactional outbox relay with retry/dead-letter and an idempotent receiver. None of
those cores (accounts, rate limiting, allocation, queue, FSM tables, pricing, vector clocks) is reproduced.
Assumptions: standard library only; in-memory database adapter; time comes from an injected `java.time.Clock`.

## Design
Dependency direction (inward only): `adapter -> port <- app -> domain`.
- `ae.outbox.domain`: `Show(id, capacity, reserved)` immutable, `reserve(n)`/`release(n)` return a new Show
  (`SoldOutException` when full); `Booking(id, showId, customer, seats, cancelled)`; `OutboxMessage` record
  (id, key, topic, payload, attempts, nextAttemptAt, status PENDING/DELIVERED/DEAD, lastError).
- `ae.outbox.port`: `Database.inTransaction(TxWork<T>)` (all-or-nothing: any exception rolls back every write),
  `Tx` (shows, bookings, booking sequence, outbox enqueue/pending/update), `MessageSink.deliver(message)`.
- `ae.outbox.adapter`: `InMemoryDatabase` (serialised transactions on a working copy, commit on success, nested or
  stale `Tx` use is rejected).
- `ae.outbox.app`: `BookingService.reserve/cancel` writes the business change AND the outbox message in one
  transaction; `OutboxRelay.runOnce()` delivers pending messages outside any transaction (never call user code under
  the lock), then records the outcome in a second transaction; `IdempotentReceiver` dedups by message id.
Rules:
- Payloads: `booking.reserved|<bookingId>|<showId>|<customer>|<seats>` and `booking.cancelled|<bookingId>|<showId>|<customer>|<seats>`;
  key = showId. Customer may not contain `|`.
- Delivery is at-least-once. Per key, messages are delivered in id order: if the head of a key fails, later messages
  of that key wait; other keys are unaffected. A failed message gets `attempts+1` and
  `nextAttemptAt = now + min(base * 2^(attempts-1), maxDelay)`; when attempts reach `maxAttempts` it becomes DEAD, and
  a DEAD message no longer blocks its key. A message is not retried before it is due.
- `InterruptedException` from the sink restores the interrupt flag and ends the run without recording a failure.

## Success criteria
Tests for domain rules, atomic rollback (sold-out and exception after enqueue leave no trace), service payloads,
relay ordering/backoff/dead-letter/per-key blocking, lost-ack redelivery + idempotent receiver, a seeded randomised
run against an oracle of published messages, and concurrent reservations never overbooking.
`mvn -q -B clean test` passes.
