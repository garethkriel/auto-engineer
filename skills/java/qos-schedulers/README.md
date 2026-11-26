# qos-schedulers

- **Description:** packet scheduling in discrete time: `FifoScheduler`, `PriorityScheduler`, `DrrScheduler` (deficit round robin), `WfqScheduler` (self-clocked fair queueing with exact lcm-scaled integer tags), per-flow `TokenBucketShaper`, non-preemptive `LinkSimulator` with tail drop, `Stats`, and an `Invariants` checker (conservation, early start, duration, overlap, work conservation, per-flow FIFO).
- **Language:** java (21, stdlib only)
- **Tags:** scheduling, fairness, drr, wfq, token-bucket, simulation, invariants, oracle-tests
- **Source round:** practice/java/20261126T2257Z
- **Tests:** literal hand traces, offline DRR oracle, naive tag-recompute WFQ oracle, tick-by-tick shaper oracle, fairness bounds, one corrupt result per invariant rule.
