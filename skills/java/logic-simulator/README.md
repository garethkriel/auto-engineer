# logic-simulator

- **Language:** Java 21 (standard library only), package `ae.logic`
- **Tags:** netlist, gate-level, event-driven simulation, transport delay, glitch, static timing, equivalence checking, bit-parallel evaluation, DFF
- **Source round:** practice/java/20261124T1156Z

Text netlist (`name = OP operands [@delay]`, `input`/`output`, `DFF`, `MUX`, `CONST0/1`) parsed and validated by `NetlistParser` (line-numbered `NetlistException`, forward refs, combinational-loop report `a -> b -> a`; DFFs break loops).

- `Netlist.eval/evalAll` levelised scalar evaluation; `evalPacked` 64 vectors per `long`.
- `Timing.criticalPath` longest delay path (inputs at 0, DFF/CONST at own delay).
- `Simulator` event queue with delta cycles, projected-value scheduling (glitches visible in `trace()`), `clock()` samples all DFFs together.
- `Equiv.check` exhaustive packed equivalence (<= 20 inputs), minimal counterexample, name-matched interfaces.

Reusable ideas: oracle-per-layer testing (truth-table literals, integer addition for adders, scalar eval vs packed vs event simulation), outcome-mix assertions for randomized runs, hand-written glitch trace literal.
Tests: `mvn -q -B test` (78 tests).
