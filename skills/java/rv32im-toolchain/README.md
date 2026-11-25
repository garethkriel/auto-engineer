# rv32im-toolchain

- **Description:** RV32IM toolchain in `ae.rv`: validated canonical `Instr` record, binary `Codec` (encode/decode, strict rejection of non-canonical words), two-pass `Assembler` (labels, ABI register names, `imm(reg)`, `.word`, pseudo-instructions incl. `li` lui+addi expansion), disassembly via `Instr.toString()`, and a `Cpu` emulator (little-endian memory, alignment/range traps, M-extension edge cases, ecall exit/print/putchar, step limit).
- **Language:** java
- **Tags:** risc-v, assembler, emulator, instruction-encoding, differential-testing, bit-manipulation
- **Source round:** practice/java/20261125T2156Z
- **Tests:** hand-derived literal encodings, random encode/decode round trips, 200k random words (accepted ones must re-encode identically), disassemble-then-assemble round trip, BigInteger oracles for mul/div/rem, programs (fibonacci, gcd, sieve, bubble sort, recursive factorial on the stack).
- **Notes:** No fence/CSR/compressed instructions. Misaligned accesses trap (a legal implementation choice). Mutation pass: 8 of 9 single mutants killed; the survivor (`a << (b & 31)` -> `a << b`) is equivalent because Java masks int shifts itself.
