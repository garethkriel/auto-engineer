# 20261126T2356Z practice: ae.wasm - WebAssembly (MVP integer subset) decoder, validator, interpreter

Level 20 (capstone). Language: Java 21, standard library only.

## Overlap check
`ls benchmark/java` names: j01-roman .. j35-minisql. The closest are j07-expr and j28-script (text expression/script
evaluators) and j32-fsm. None is a binary-format decoder + type-checking validator + stack machine; no existing skill
mentions wasm. Different core algorithm, so allowed.

## Scope (stated assumptions)
Nobody is around to ask, so the subset is fixed here:
* Value types `i32` (0x7F) and `i64` (0x7E) only. No floats, no tables/elements, no imports, no multi-value
  (a function or block has at most ONE result), no block type indices (block type is 0x40, 0x7F or 0x7E).
* One optional memory, little-endian, page = 65536 bytes, runtime cap 256 pages (grow beyond -> returns -1).
* Sections: custom(0, skipped), type(1), function(3), memory(5), global(6), export(7), start(8), code(10), data(11).
  Sections import(2), table(4), element(9), datacount(12) -> `INVALID` "unsupported section". Section ids must be
  strictly increasing (custom sections may appear anywhere). Unknown id > 12 -> `MALFORMED`.

## Public API (package `ae.wasm`)
* `ModuleParser.parse(byte[]) -> Module` : decode AND validate every function body. Throws `ModuleException`
  (unchecked) with `kind()` = `MALFORMED` (binary format violation: bad magic/version, truncated, overlong LEB128,
  section size mismatch, bad UTF-8 name, unknown opcode, bad flag byte) or `INVALID` (well-formed but fails
  validation: type mismatch, stack underflow, unknown local/global/function/type/label, immutable global.set,
  alignment larger than natural, duplicate export, bad start function type, constant-expression type mismatch,
  too many locals (> 50000), if-without-else with a result). Messages of `MALFORMED` start with
  `malformed at offset N: `. No other exception type may ever escape `parse` (checked by truncation and byte-flip loops).
* `Instance.instantiate(Module) -> Instance` : allocates memory/globals, copies data segments (a segment that does
  not fit -> `ModuleException` kind `LINK`, message "data segment does not fit"), runs the start function.
  `Instance.instantiate(Module, long fuel)` sets the fuel budget.
* `Instance.invoke(String export, long... args) -> long[]` : arguments and results use one `long` per value.
  An `i32` value is the SIGN-EXTENDED int (`(long)(int)x`); on input only the low 32 bits are used. Wrong
  export name/kind or wrong argument count -> `IllegalArgumentException`.
* `Instance.memoryPages()`, `readMemory(addr,len)` (copy), `global(String)` for exported globals.
* Traps: `Trap` (unchecked) with `kind()`: `UNREACHABLE, DIV_ZERO, INT_OVERFLOW, OUT_OF_BOUNDS, STACK_EXHAUSTED,
  OUT_OF_FUEL`. Fuel: 1 unit per taken branch (`br`, taken `br_if`, `br_table`) and per `call`; running out traps.
  Call depth limit 300 -> `STACK_EXHAUSTED`. Memory and globals keep their state after a trap.

## Supported instructions
control `unreachable nop block loop if else end br br_if br_table return call`; `drop select`;
`local.get/set/tee global.get/set`; `i32.load i64.load i32.load8_s/u i32.load16_s/u i32.store i64.store
i32.store8 i32.store16 memory.size memory.grow`; `i32.const i64.const`; all i32/i64 comparisons (`eqz eq ne lt_s lt_u
gt_s gt_u le_s le_u ge_s ge_u`), integer arithmetic (`clz ctz popcnt add sub mul div_s div_u rem_s rem_u and or xor
shl shr_s shr_u rotl rotr`), `i32.wrap_i64 i64.extend_i32_s i64.extend_i32_u`, sign extension
`i32.extend8_s i32.extend16_s i64.extend8_s i64.extend16_s i64.extend32_s`.

## Semantics fixed by this task
* Effective address = unsigned(base) + unsigned(offset) (no wrap); access traps `OUT_OF_BOUNDS` if `ea + size > memory bytes`.
* `div_s`: divisor 0 -> DIV_ZERO; MIN / -1 -> INT_OVERFLOW; `rem_s` MIN % -1 = 0. `div_u/rem_u`: divisor 0 -> DIV_ZERO.
* Shift and rotate counts are taken modulo the bit width. `clz(0)=ctz(0)=width`.
* `memory.grow(d)` returns old page count, or -1 if old+d exceeds min(declared max, 256); memory contents preserved.
* Validation follows the spec's stack-polymorphic algorithm: after `unreachable/br/br_table/return` the rest of the
  block is checked with an unknown-typed polymorphic stack.
* `br_table` : all labels must have the same arity/type as the default label (here: result type or none).
* Integer decoding is strict: LEB128 u32 <= 5 bytes (5th byte < 16), s32 <= 5, s64 <= 10; unused bits of the last
  byte must be zero (unsigned) or replicate the sign bit (signed); no over-long encodings.
* Counts read from the binary are checked against remaining bytes BEFORE allocation (no OOM from a hostile count).

## Success criteria
* `mvn -q -B clean test` passes: literal-vector tests, per-opcode edge tables, invalid-module table with message
  checks, a control-flow program suite (factorial, fib, gcd, collatz, sieve in memory, br_table dispatch, recursion),
  a randomized differential test (random typed expression trees compiled to wasm vs an independent BigInteger evaluator),
  truncation + every-byte-flip robustness loops, and fuel/stack traps.
* A mutation pass (script in the round folder) kills every non-equivalent mutant; survivors are explained in the lesson.
