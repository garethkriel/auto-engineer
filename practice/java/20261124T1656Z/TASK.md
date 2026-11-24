# 20261124T1656Z — ae.wheel: hierarchical timing wheel (level 20 capstone)

## Task
Build `ae.wheel.TimerWheel`, a single-threaded, virtual-time timer service (no real clock, no threads):

* `schedule(delay, Runnable)` one-shot; `scheduleAtFixedRate(initialDelay, period, Runnable)` periodic.
* `advanceTo(target)` moves virtual time forward and runs every due timer, in `(deadline, schedule-sequence)` order.
  `now()` equals the timer's deadline while its callback runs. Returns the number of callbacks run.
* `Handle.cancel()`, `isDone()`, `deadline()`; `size()` (pending timers), `nextDeadline()` (earliest pending).
* Must jump over idle time: `advanceTo(10^12)` with 3 timers must not loop per tick.

## Overlap check
Listed `benchmark/java/` (j01-roman .. j35-minisql). Nearest: j13-ratelimit (token bucket), j17-cache (expiring LRU),
j20-cron (cron fields), j24-queue (blocking queue). None is a timer-wheel / virtual-time scheduler. No existing skill
is a timing wheel (job-scheduler is persistent retries, retry-backoff is delay arithmetic).
Reviewed rejected PR #136 (overlap with j18-diff): lesson applied, titles checked above.

## Design
* 4 levels x 64 slots (6 bits per level). A timer with deadline `d` at time `now` goes to level
  `L = highestDifferingGroup(d ^ now)` and slot `(d >> 6L) & 63`. `d ^ now >= 2^24` goes to an overflow
  min-heap, pulled in when `d >> 24 == now >> 24`.
* Each level has a 64-bit occupancy mask. The next *event* (a non-empty level-0 slot, or a higher-level slot
  boundary that needs a cascade, or an overflow pull) is found with bit scans, so time jumps skip idle ticks.
* Cascade at time `T` (low `6L` bits zero): re-link the level-`L` slot against the new `now`, highest level first.
* Slots are intrusive doubly linked lists (O(1) cancel); firing a level-0 slot sorts by sequence number.
* Timer states: PENDING (linked), QUEUED (in the batch being fired), RUNNING, DONE.
* Periodic timers are fixed-rate: next deadline = previous deadline + period (no drift, catch-up on big jumps),
  re-armed after the callback with a fresh sequence number unless cancelled inside it. Deadline overflow ends it.
* A callback may schedule (delay 0 fires in the same `advanceTo`, after the current batch) and cancel timers
  (including QUEUED ones in the same batch). It must not call `advanceTo` (IllegalStateException).
* Callback `RuntimeException`s do not stop the run: the rest still fires, time reaches `target`, then
  `TimerWheel.TimerFailure` is thrown with the first failure as cause and the others suppressed.
* Validation is side-effect free: negative delay, `period < 1`, null task, delay overflowing `Long.MAX_VALUE`,
  `target < now` -> IllegalArgumentException.

## Success criteria
* `mvn -q -B clean test` passes: literal tests, a package-private invariant checker run after every step of
  randomized differential tests against a `PriorityQueue` oracle (callbacks that schedule/cancel), cascade
  boundary tests, a jump test and a time-bounded 200k-timer test.
* Hand mutation check of the main rules.
