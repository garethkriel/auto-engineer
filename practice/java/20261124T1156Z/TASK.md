# Round 20261124T1156Z - Level 20 capstone: gate-level logic simulator `ae.logic`

## Task
Package `ae.logic`, standard library only.

1. Netlist text format and `NetlistParser.parse(String)` -> `Netlist` (immutable):
   ```
   # comment
   input a b cin
   output s cout
   p = XOR a b @2      # optional "@delay" last token, integer 0..1000, default 1
   q = DFF d           # D flip-flop, delay = clock-to-q (default 1)
   k = CONST1          # CONST0/CONST1 take no operands, default delay 0
   ```
   Ops: `AND OR XOR NAND NOR XNOR` (>= 2 operands), `NOT BUF DFF` (1), `MUX sel a b` (= sel ? b : a), `CONST0 CONST1`.
   Operands may be forward references (needed for DFF feedback). Errors throw `NetlistException` carrying a
   1-based line number: bad syntax, duplicate name, unknown op, wrong arity, bad delay, unknown operand, unknown
   output, duplicate output, name equal to a keyword/op, and a combinational loop (DFF breaks loops). Loop message:
   `combinational loop: a -> b -> a` (each depends on the next; lowest declaration index first).
2. `Netlist.eval(Map inputs[, Map dffState])` levelised evaluation (missing inputs/state = false) and
   `evalPacked(long[])`: 64 input vectors per pass (bit k of word i = value of input i in vector k); combinational only.
3. `Timing.criticalPath(Netlist)`: longest sum of delays (inputs arrive 0, DFF outputs at their delay), with the path.
4. `Simulator`: event-driven, transport delays, delta cycles for delay 0. `setInput`, `run()` returns settle duration
   (time of last value change minus start, 0 if none), `clock()` samples every DFF D at once then updates Q after
   the DFF delay, `value(name)`, `time()`, `trace()` (list of time/signal/value changes, glitches included).
5. `Equiv.check(a, b)`: exhaustive combinational equivalence (<= 20 inputs) via packed evaluation; returns the
   counterexample with the smallest input index (first-declared input = LSB), or empty.

## Success criteria
- Parser rejects each error class with the right line; accepts forward refs and DFF feedback.
- Ripple-carry and carry-lookahead adders equal integer addition (exhaustive for 4 bits, random for 16) and each other (Equiv).
- A buggy adder yields the minimal counterexample, which really differs under scalar eval.
- Packed eval == scalar eval on randomly generated circuits; event simulation final values == levelised eval; settle time <= critical path.
- Glitch trace on an unequal-delay hazard circuit equals a hand-written literal list; a 4-bit counter counts over 40 clocks.
- Hand mutation script kills most mutants.

## Assumptions / overlap check
- benchmark j01..j35 has no circuit/netlist/simulator task (j15-topo is plain topological sort, j32-fsm a string FSM); skill list has no logic simulator.
- Zero-delay combinational loops cannot occur because loops are rejected at parse time.
