# Task: ae.mc explicit-state model checker (level 20, capstone systems)

Library `ae.mc` that exhaustively explores the state space of a transition system and either proves a property or returns a minimal counterexample. Chosen to be disjoint from every `benchmark/` task (checked the titles: no model checking, no state-space exploration there).

## API
- `Model<S>`: `List<S> initial()`, `List<Move<S>> moves(S)`; `Move(label, target)`. States need value `equals/hashCode`.
- `Step<S>(label, state)`: trace element. First step of a trace has label `"init"`.
- `Verdict<S>` (sealed): `Holds(states, transitions)`, `InvariantViolated(name, trace)`, `Deadlock(trace)`, `LivenessViolated(name, prefix, cycle)`, `Incomplete(states)`.
- `ModelChecker.checkSafety(model, invariants /*ordered map name->predicate*/, isTerminal, maxStates)`: BFS; every discovered state (initial ones included) is checked against all invariants in map order; a state with no moves that is not terminal is a `Deadlock`. Counterexample traces are shortest. Exceeding `maxStates` distinct states before a verdict -> `Incomplete`.
- `ModelChecker.checkEventually(model, name, goal, maxStates)`: property "on every infinite execution, goal eventually holds". Terminal states (no moves) stutter forever. Violation = a reachable path avoiding goal states that ends in a cycle (or a stuck state) which also avoids goal. Returns `prefix` (init ... first cycle state, shortest) and `cycle` (steps returning to the first cycle state; EMPTY for a stuck terminal state).
- `Examples`: Peterson mutex, flag-only mutex (deadlocks), check-then-set mutex (unsafe), dining philosophers (naive / ordered), water jugs.

## Assumptions
- Explicit exploration is bounded; maxStates counts distinct states including initial ones.
- Duplicate initial states are collapsed.

## Success criteria
`mvn -q -B clean test` passes: known-answer tests (jugs: 6-step solution; 2/4 jugs unreachable 3 with 6 states), Peterson safe, check-then-set unsafe with shortest trace, philosophers deadlock vs ordered fix, liveness lasso validity, randomized differential tests vs brute-force oracles (BFS distances; greatest fixpoint of EG).
