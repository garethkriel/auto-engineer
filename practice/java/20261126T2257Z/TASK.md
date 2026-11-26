# 20261126T2257Z: packet scheduling and traffic shaping (`ae.qos`)

Level 20 capstone (python tools/curriculum.py java printed 20). Java 21, standard library only.

**Overlap check** (`ls benchmark/java`): j13-ratelimit is a single token bucket with a nano clock, j24-queue is a blocking queue, j10-counter/j09-bank are unrelated. This task is a link-level packet scheduler family (FIFO, strict priority, DRR, WFQ) plus a per-flow shaper and a discrete-time link simulator; no benchmark name matches and no existing skill covers it.

## Design

All times are `long` ticks, sizes are bytes, link rate is `long` bytes per tick. A packet is sent non-preemptively and takes `ceil(size / rate)` ticks.

- `Packet(int id, String flow, int size, long arrival)`: flow not blank, size > 0, arrival >= 0.
- `Scheduler` (interface): `enqueue(Packet)`, `Optional<Packet> dequeue()`, `long backlogBytes()`, `isEmpty()`. Unknown flow -> `IllegalArgumentException`, thrown before any state changes.
- `FifoScheduler`: arrival (enqueue) order.
- `PriorityScheduler(Map<String,Integer>)`: smaller number = higher priority; strict; FIFO among equal priority (by enqueue order, across flows).
- `DrrScheduler(Map<String,Integer> quanta)` (deficit round robin, quanta >= 1):
  - Active list holds flows with queued packets, in the order they became active. A flow that becomes active is appended and starts with deficit 0.
  - When a flow reaches the head of the active list and has not yet been credited this turn, `deficit += quantum`. It then sends head packets while `size <= deficit`, one per `dequeue()`, subtracting sizes.
  - If the head packet does not fit, the flow moves to the tail (deficit kept, turn ends). If the queue empties, deficit is reset to 0 and the flow leaves the active list (no credit hoarding).
- `WfqScheduler(Map<String,Integer> weights)` (self-clocked fair queueing, weights >= 1):
  - `scale = lcm(weights)` so tags are exact integers.
  - On enqueue: `start = max(lastFinish[flow], V)`, `finish = start + size * (scale / weight)`. `V` is the finish tag of the packet most recently dequeued (0 initially). Dequeue picks the smallest `(finish, enqueue seq)`.
  - Overflow of any tag arithmetic throws `ArithmeticException` with the scheduler unchanged.
- `TokenBucketShaper.shape(List<Packet>, long rate, long burst)`: each flow has its own bucket (capacity `burst` bytes, refill `rate` bytes per tick, integer, starts full). A packet is released at the earliest tick `>= max(arrival, previous release of that flow)` where the bucket holds `size` tokens. Returns packets with `arrival` replaced by the release tick, sorted by `(release, input position after a stable sort by arrival)`. `size > burst` -> IAE.
- `LinkSimulator.run(packets, scheduler, bytesPerTick, bufferBytes)`:
  - Input is stable-sorted by arrival; ids must be unique.
  - At each step all packets with `arrival <= now` are admitted in input order. A packet is admitted iff `backlogBytes + size <= bufferBytes` (the packet in service is not in the backlog), otherwise it is tail-dropped.
  - Then the scheduler is asked for the next packet; if empty, `now` jumps to the next arrival.
  - Result: `Departure(packet, start, end)` list in service order plus the dropped packets in drop order.
- `Invariants.check(input, result, rate)` returns violation strings (empty = ok), each prefixed with its rule name: `conservation:`, `early-start:`, `duration:`, `overlap:`, `idle:` (work conservation), `flow-order:` (per flow FIFO).

## Success criteria

1. Hand-derived literal traces for DRR, WFQ, priority, shaper, simulator (including idle gap and drop).
2. Properties over seeded random workloads: every scheduler passes `Invariants`; DRR equals an offline round-based oracle on all-at-once backlogs; WFQ equals a naive recompute-all-tags oracle; the shaper equals a tick-by-tick oracle and satisfies the token-bucket envelope; fairness bounds hold for continuously backlogged flows.
3. Invariants has one corrupt-result test per rule.
4. Mutation pass over the main sources; every survivor either killed by a new test or justified as equivalent.
5. `mvn -q -B clean test` passes from a clean `target/`.

## Assumptions

- Fairness bound over the prefix where all flows are still backlogged: DRR `|B_i/q_i - B_j/q_j| <= 2 + Lmax/q_i + Lmax/q_j`-style bounds are checked in integer form in the tests; WFQ `|B_i*w_j - B_j*w_i| <= Lmax*(w_i+w_j)` (self-clocked bound). If a bound fails the cause is analysed, not the bound loosened blindly.
