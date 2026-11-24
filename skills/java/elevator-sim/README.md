# elevator-sim

- **Description:** Deterministic tick-based elevator-bank simulation: FIFO floor queues, hall calls dispatched by cost (distance + 3 per queued stop, ties lowest id), SCAN-style stops, capacity, per-passenger board/deliver ticks, and `checkInvariants()` (capacity, accounting, call coverage). Includes the livelock fix: a car at the call's floor that cannot board is ineligible.
- **Language:** java
- **Tags:** simulation, dispatch, state-machine, invariants, livelock, deterministic
- **Source round:** practice/java/20261124T1957Z

Usage: `new Simulation(floors, cars, capacity, passengers).run(maxTicks)` returns `Result` with `boardTick(id)`, `deliverTick(id)`.
