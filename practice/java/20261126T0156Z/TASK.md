# 20261126T0156Z — JVM class-file toolkit (`ae.classfile`)

Level 20 capstone. Overlap check: `benchmark/java/` j01..j35 (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql) contain no binary-format/bytecode task; existing skills have none either.

## Task
Build a strict reader, writer, disassembler and stack-depth analyzer for JVM `.class` files, verified against the JDK itself (`javap`, reflection, `DataOutputStream.writeUTF`).

## Design
- `ModifiedUtf8`: strict JVMS 4.4.7 codec. Reject raw 0x00, 0xF0..0xFF lead bytes, truncation, bad continuations and overlong forms (NUL is only `C0 80`).
- `Constant` sealed records + `ConstantPool` (1-based, Long/Double take two slots, slot after is invalid).
- `ClassReader.parse(byte[])` -> `ClassFile`: magic, major 45..65, tags, reference-type checks, `this/super/interfaces`, fields/methods with descriptor and name validity, duplicate member rejection, Code-attribute rule (abstract/native => none, else exactly one), Code body parse (exception table ranges), attributes within bounds, no trailing bytes.
- `ClassWriter.toBytes(ClassFile)`: exact inverse; every JDK class must round-trip byte-identically.
- `Descriptor`: field/method descriptors, slot counts, 255-slot and 255-dimension limits.
- `Opcodes`, `Instruction`, `Disassembler.decode(byte[])`: all opcodes, wide, switches (padding computed from offset, sorted lookup keys, low<=high), operand validation (newarray atype 4..11, invokeinterface/invokedynamic zero byte, multianewarray dims>=1), `validateTargets`: branch targets land on instruction starts and inside the code. `render` prints javap-style operand text. Switch padding bytes are not checked (JVMS leaves them unspecified).
- `CodeAnalyzer.analyze(ClassFile, Member)`: abstract interpretation over the CFG with per-opcode (pops, pushes) in slots; invoke/field/ldc sizes come from the pool descriptors; handlers start at depth 1; depths must agree at merges; underflow, falling off the end and jsr/ret are errors; unreachable code is ignored. Result: max stack depth, highest local slot used, instruction count.

## Success criteria
`mvn -q -B test` green. Oracles: javap -c -p instruction streams (offset, mnemonic, operands) for fixtures and ~30 JDK classes; reflection for class/field/method sets; writeUTF/readUTF differential for modified UTF-8; byte-identical round trip and analyzer-vs-declared `max_stack` over every `java/util` and `java/lang` class in the running JDK. Strict-negative tests use crafted images with a control differing only by the defect.

## Assumptions
Assumes JDK 21 `javap` is reachable through `java.util.spi.ToolProvider` (a missing tool fails the test loudly). Stack depth is counted in slots (long/double = 2). Where the analyzer's maximum differs from declared `max_stack` the test asserts `analyzed <= declared` always and records the exact-match rate.
