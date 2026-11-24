# Task: elevator-bank dispatch simulation (level 20 capstone, topic: small end-to-end deterministic simulation)

Overlap check: benchmark/java ids are roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue,
bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql. None is a lift/elevator or
discrete-tick building simulation (j32-fsm is a generic state machine; this task is a multi-agent dispatch simulation).

## Design
Package `ae.elevator`:
- `Direction` enum UP/DOWN. `Passenger(String id, int arrivalTick, int from, int to)` record (from != to).
- `Simulation(int floors, int cars, int capacity, List<Passenger>)`: cars start at floor 0, direction UP, empty.
- `step()` processes one tick `t` (starting at 0) in this fixed order:
  1. Passengers with `arrivalTick == t` join the FIFO queue of their floor. If no call (floor, dir) is pending, a call is registered.
  2. Dispatch: unassigned calls in (floor, dir) order are each given to the car minimising
     `|car.floor - call.floor| + 3 * car.stops.size()` (ties: lowest car id). A car standing on the call's floor that cannot board that call (full, or riders going the other way) is
     ineligible; if no car is eligible the call stays unassigned until next tick; the car adds the floor to its stops.
  3. Each car, in id order, acts: if its floor is in its stops it opens doors (the tick is consumed):
     unload riders with dest == floor (delivery tick = t); remove floor from stops; clear the pending call
     for (floor, UP) and (floor, DOWN) only for the direction it boards; boarding direction = car direction if it still has
     riders, otherwise the direction of the first waiting passenger on the floor (the car adopts it). It boards waiting passengers
     of that direction in FIFO order up to capacity, adds each destination to stops. If passengers of a direction remain waiting
     at the floor after boarding (and a call is not pending) the call is re-registered.
     Otherwise, if stops non-empty, it moves one floor: continue in current direction if stops exist ahead, else reverse and move.
     No stops: idle (direction kept).
- `run(maxTicks)` steps until all passengers delivered; throws IllegalStateException when the bound is hit.
- `checkInvariants()`: capacity respected, floors in range, each passenger in exactly one of
  {not yet arrived, waiting, riding, delivered}, each waiting passenger has a pending call, each rider's destination is in its car's stops.
- `Result`: per passenger board tick and delivery tick, `waitTicks`, `rideTicks`, `deliveredCount`.

## Success criteria
`mvn -q -B clean test` passes: hand-derived traces, invariants after every tick on seeded random runs, liveness
(all delivered within a bound), delivery lower bound `arrival + |from-to|`, mutation-style negative controls.
