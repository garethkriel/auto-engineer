# minilang-toolchain

- **Description:** a complete tiny-language toolchain in package `ae.minilang`: lexer with positions, precedence-climbing
  parser, static checker (names, arity, scopes), reference tree interpreter, semantics-preserving AST optimiser
  (constant folding that never folds away an error, dead branches, pure statements, code after return, fixpoint),
  bytecode compiler, stack-depth bytecode `Verifier`, and a stack VM; `Lang.run(src, Engine)` runs any of four
  engines (`INTERPRETER`, `INTERPRETER_OPTIMIZED`, `VM`, `VM_OPTIMIZED`) returning output plus error kind.
- **Language:** java (JDK 21, JUnit 5 only)
- **Tags:** interpreter, compiler, bytecode, vm, verifier, optimizer, parser, differential-testing, program-generator
- **Source round:** `practice/java/20261120T1356Z`
- **Tests:** 64. Literal tests per feature, BigInteger oracle for arithmetic, crafted-bytecode verifier cases (one
  defect each plus controls), optimiser rewrite table and idempotence, and a seeded generator of terminating programs
  (1500 seeds; outcome mix about 38% clean, 35% DIV_ZERO, 27% OVERFLOW) checked across all four engines.

Reuse notes: keep one `Arith` for both engines and test it against `BigInteger`; compare engines on (output, error
kind) so output printed before an error is part of the contract; generate programs as ASTs and print them, so the
generator cannot emit syntax errors and the printer round trip is itself a test. Mutation check: 11/12 mutants killed
(the survivor, `Long.MIN_VALUE % -1` special case, is equivalent in Java).
