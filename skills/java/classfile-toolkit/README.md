# classfile-toolkit

- **Description:** strict JVM class-file toolkit in `ae.classfile`: modified UTF-8 codec, constant pool and `ClassReader`/`ClassWriter` (byte-exact round trip), descriptor parser, full-opcode disassembler with javap-style rendering and branch-target validation, and a stack-depth/locals `CodeAnalyzer` (abstract interpretation with merge checks and handler entry depth).
- **Language:** java (JDK 21, standard library only)
- **Tags:** bytecode, class-file, binary-format, disassembler, strict-decoder, differential-testing, javap, stack-analysis
- **Source round:** `practice/java/20261126T0156Z`
- **Tests:** `mvn -q -B test` (170 tests). Oracles are the JDK itself: `javap -c -p` on ~28 classes plus a synthetic class containing every opcode, reflection, `DataOutputStream.writeUTF`, and a whole-`java.util`/`java.lang` round-trip with analyzed vs declared `max_stack` (22137 methods, all exact). Needs `javap` (full JDK) at test time.

## Reuse notes
- `ClassReader.parse(byte[])` throws `ClassFormatException` for every defect (unchecked); `ClassFile.code(member)` decodes Code on demand.
- Constants keep raw float/double bits, so NaN payloads round-trip.
- `CodeAnalyzer` rejects jsr/ret (old class files) and counts depth in slots.
- Test pattern worth copying: `Img` builds crafted images independently of the writer; each defect has a control differing only by that defect; flip-every-byte property "clean rejection or identical re-serialisation".
