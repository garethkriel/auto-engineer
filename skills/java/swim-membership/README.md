# swim-membership

- **Description:** SWIM failure detector and membership (`ae.swim`): `SwimNode` pure state machine (`tick(now)` / `receive(msg, now)` return messages) with round-robin direct probes, ping-req indirect probes, suspicion with timeout, incarnation-number refutation, DEAD as absorbing, piggybacked gossip with per-update retransmit cap and a targeted "what I suspect about you" payload; `Simulation` with latency 1, seeded loss, crashes, blocked links, message trace; `Invariants` checker run after each tick.
- **Language:** Java 21, standard library only
- **Tags:** distributed, gossip, failure-detector, swim, simulation, invariants, seeded-tests
- **Source round:** practice/java/20261119T1556Z
- **Reusable ideas:** suspicion timeout must exceed gossip spread time (about 8 periods for 8 nodes) or false DEADs appear; the probe period must cover the indirect round trip (timeout + 4 ticks of latency); a fully isolated node buries everyone else and has nobody left to probe, so there is no rejoin without a push-pull/join mechanism; `debugForce` hook plus `checkInvariants()` proves the checker can fail.
- **Run tests:** `mvn -q -B test`
