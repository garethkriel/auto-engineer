# SWIM-style membership and failure detection, simulated in-process (level 20 capstone)

Overlap check (benchmark/java): j01..j35 list (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours,
rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is a gossip membership or
failure-detector task (j34-vclock is vector clocks only). No overlap.

## Task
Build a deterministic, seeded simulation of the SWIM protocol (scalable weakly-consistent infection-style process-group
membership): ping / indirect ping-req probing, suspicion with refutation via incarnation numbers, and piggybacked
gossip dissemination. Standard library only, package `ae.swim`.

## Design
* `Status {ALIVE, SUSPECT, DEAD}`, `Update(node, status, incarnation)` with `overrides(other)`:
  DEAD is absorbing; ALIVE(i) beats ALIVE/SUSPECT(j) iff i > j; SUSPECT(i) beats ALIVE(j) iff i >= j and
  SUSPECT(j) iff i > j. Nothing overrides DEAD, and DEAD overrides everything else.
* `Message` (sealed): `Ping(from,to,seq,updates)`, `PingReq(from,to,target,seq,updates)` (ask `to` to probe `target`),
  `Ack(from,to,seq,about,updates)` (`about` = the node proven alive; a relayed ack keeps the original seq).
* `Config(periodTicks, pingTimeoutTicks, indirectProbes, suspicionTicks, maxPiggyback, retransmitMult)`.
* `SwimNode`: pure state machine, `tick(now)` and `receive(msg, now)` return the messages to send.
  Every period: probe the next peer of a shuffled round-robin; if no ack after `pingTimeoutTicks` send
  `indirectProbes` ping-reqs; at `periodTicks` without ack mark the target SUSPECT (at its known incarnation).
  A SUSPECT member that is not refuted within `suspicionTicks` becomes DEAD. A node told SUSPECT about itself with
  incarnation >= its own bumps its incarnation and gossips ALIVE (refutation). A node told it is DEAD stops.
  Updates are piggybacked on every message, each at most `retransmitMult * ceil(log2(n+1))` times, least-sent first,
  at most `maxPiggyback` per message; a message to X also carries what the sender believes SUSPECT about X.
* `Simulation`: lossless-by-default network with latency 1 tick, seeded drops, crashed nodes, blocked links,
  a message trace, and an invariant checker run after every tick.

## Invariants (checked after every tick)
1. DEAD is absorbing in every live observer's table.
2. An observer's incarnation for a subject never decreases.
3. No observer ever holds an incarnation for a subject larger than the subject's own incarnation.

## Success criteria
* Update override table matches the literal table.
* Unit protocol tests: ack, relay, suspicion timing, refutation, self-dead, piggyback caps.
* Simulation: no false positives on a lossless network; every crashed node DEAD at every live node within a
  bound; one bad link is masked by indirect probes; partition healed before the suspicion timeout ends with all
  ALIVE and refuted incarnations; lossy network never produces DEAD for live nodes (seeded runs); same seed => same
  trace; the invariant checker catches a forced resurrection and a forged incarnation.
