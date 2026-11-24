# timer-wheel

- **Language:** Java 21 (standard library only)
- **Tags:** timer, timing-wheel, scheduler, virtual-time, hierarchical-wheel, bitmask, periodic, cancellation
- **Source round:** `practice/java/20261124T1656Z`

`ae.wheel.TimerWheel`: single-threaded virtual-time timer service. 4 levels x 64 slots plus an overflow heap, per-level occupancy bitmasks so `advanceTo` jumps event to event (idle time is free), `(deadline, sequence)` firing order, one-shot and fixed-rate periodic timers (re-armed with a fresh sequence number, catch-up on big jumps), O(1) cancel via intrusive lists, reentrant callbacks (schedule/cancel, zero delay fires in the same advance), failure collection (`TimerFailure`), `nextDeadline()`.

Reusable ideas: place by highest differing 6-bit group of `deadline ^ now`; cascade highest level first at block starts; package-private `checkInvariants()` run after every step of randomized differential tests against a `PriorityQueue` oracle.

Tests: `mvn -q -B test` (42 tests: literal order, cascade boundary table over start times x delays, cancel/periodic/reentrancy/failure semantics, 200k-timer time-bounded run, seeded programs vs oracle).
