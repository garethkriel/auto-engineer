# Task: ae.rv — RV32IM assembler, codec and emulator (level 20 capstone)

Overlap check: `benchmark/java/` names (j01-roman … j35-minisql: roman, wordfreq, lru, csv, intervals, lcs, expr,
graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex,
segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql) — none is a
CPU/instruction-set task. `j28-script` is a scripting language; this is a binary ISA, assembler and machine emulator.

## Design (package `ae.rv`)
- `Op` enum: every instruction with format (R, I, SH, S, B, U, J, SYS), opcode, funct3, funct7.
  RV32I base (no fence/CSR) + M extension.
- `Instr` record `(op, rd, rs1, rs2, imm)`: canonical (unused fields must be 0), validated ranges:
  I/S: -2048..2047; SH: 0..31; B: even -4096..4094; J: even -2^20..2^20-2; U: 0..0xFFFFF (the 20-bit upper value).
  `toString()` prints assembler syntax with numeric branch offsets.
- `Codec.encode(Instr) -> int`, `Codec.decode(int) -> Instr` (throws `IllegalInstructionException` for unknown or
  non-canonical words; `encode(decode(w)) == w` for every accepted word).
- `Assembler.assemble(String) -> Program(int[] words, Map<String,Integer> labels)`, base address 0, two passes.
  Syntax: `label:`, `# comment`, ABI/`xN` registers, `imm(reg)` operands, decimal/hex immediates, `.word n`.
  Pseudo-instructions: nop, li, mv, not, neg, seqz, snez, j, jal L, jr, ret, beqz, bnez, bltz, bgez, bgt, ble, bgtu, bleu.
  `li` is `addi` when -2048..2047 else `lui`+`addi` (addi omitted if low 12 bits are 0; upper part adjusted for sign of low).
  Branch/jump targets are labels or numeric byte offsets. Errors: `AsmException` with message prefix `line N:`.
- `Cpu(memBytes)`: 32 registers (x0 hard-wired 0), pc, little-endian byte memory, sp (x2) = memBytes at reset.
  Misaligned loads/stores/fetch, out-of-range access and illegal instructions throw `Trap`.
  M semantics: div by 0 -> -1 / 0xFFFFFFFF, rem by 0 -> dividend, MIN/-1 -> MIN rem 0; mulh* use 64-bit products.
  ecall: a7=93 exit(a0); a7=1 print a0 as signed decimal + "\n"; a7=11 putchar(a0 & 0xFF); else Trap. ebreak halts.
  `run(maxSteps)` returns `RunResult(halt, exitCode, steps, output)`, halt in {EXIT, BREAK, STEP_LIMIT}.

## Success criteria
- Literal hand-derived encodings match; decode/encode round-trip on random instructions and 200k random words.
- Assembler -> disassembler (toString) -> assembler round trip for random instructions.
- Programs (fibonacci, gcd, sieve, bubble sort, recursive factorial with stack) produce literal expected results.
- M-extension and shift semantics checked against `BigInteger` oracles and edge inputs.
- `mvn -q -B test` passes in this folder.
