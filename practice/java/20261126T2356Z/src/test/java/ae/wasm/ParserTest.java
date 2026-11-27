package ae.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ParserTest {
    private static final byte[] HEADER = Bin.bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00);

    private static ModuleException malformed(byte[] image) {
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(image));
        assertEquals(ModuleException.Kind.MALFORMED, e.kind(), e.getMessage());
        assertTrue(e.getMessage().startsWith("malformed at offset "), e.getMessage());
        return e;
    }

    private static ModuleException invalid(byte[] image) {
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(image));
        assertEquals(ModuleException.Kind.INVALID, e.kind(), e.getMessage());
        return e;
    }

    private static Bin.M richModule() {
        Bin.M m = new Bin.M();
        int t = m.type("ii:i");
        int t0 = m.type(":");
        int g = m.global('i', true, 5);
        m.mem(1, 2);
        int add = m.func(t, "l", Bin.c().get(0).get(1).b(0x6A));
        int st = m.func(t0, "", Bin.c().i32(7).gset(g));
        m.exportFunc("add", add);
        m.export("mem", 2, 0);
        m.export("g", 3, g);
        m.start(st);
        m.data(8, new byte[] {1, 2, 3});
        m.custom = Bin.cat(Bin.u(4), "note".getBytes(StandardCharsets.UTF_8), Bin.bytes(9, 9, 9));
        return m;
    }

    @Test
    void emptyModuleParses() {
        Module m = ModuleParser.parse(HEADER);
        assertEquals(0, m.functionCount());
    }

    @Test
    void headerErrors() {
        malformed(new byte[0]);
        malformed(Bin.bytes(0x00, 0x61, 0x73));
        malformed(Bin.bytes(0x00, 0x61, 0x73, 0x6E, 0x01, 0x00, 0x00, 0x00));
        ModuleException v = malformed(Bin.bytes(0x00, 0x61, 0x73, 0x6D, 0x02, 0x00, 0x00, 0x00));
        assertTrue(v.getMessage().contains("unknown binary version"), v.getMessage());
        ModuleException magic = malformed(Bin.bytes(1, 2, 3, 4, 1, 0, 0, 0));
        assertTrue(magic.getMessage().contains("magic"), magic.getMessage());
    }

    @Test
    void richModuleParsesAndRuns() {
        Module m = ModuleParser.parse(richModule().build());
        assertEquals(2, m.functionCount());
        Instance inst = Instance.instantiate(m);
        assertEquals(7L, inst.global("g")); // start function ran
        assertEquals(1, inst.memoryPages());
        assertEquals(5L, inst.invoke("add", 2, 3)[0]);
    }

    @Test
    void sectionOrderAndDuplicates() {
        Bin.M m = richModule();
        byte[] image = m.build();
        // swap: append a second type section after everything -> id 1 after id 11
        byte[] extra = Bin.cat(image, Bin.bytes(1, 1, 0));
        ModuleException e = malformed(extra);
        assertTrue(e.getMessage().contains("unexpected section order"), e.getMessage());
        // duplicate: two memory sections
        byte[] one = Bin.cat(HEADER, Bin.bytes(5, 3, 1, 0, 1));
        ModuleParser.parse(one); // control: a single memory section is fine
        byte[] dup = Bin.cat(one, Bin.bytes(5, 3, 1, 0, 1));
        ModuleException d = malformed(dup);
        assertTrue(d.getMessage().contains("unexpected section order"), d.getMessage());
    }

    @Test
    void customSectionsAnywhereAreSkipped() {
        byte[] custom = Bin.cat(Bin.bytes(0, 5), Bin.bytes(4), "abcd".getBytes(StandardCharsets.UTF_8));
        Bin.M m = new Bin.M();
        int t = m.type(":i");
        int f = m.func(t, "", Bin.c().i32(42));
        m.exportFunc("f", f);
        byte[] img = m.build();
        byte[] withTrailingCustom = Bin.cat(img, custom);
        byte[] withLeadingCustom = Bin.cat(HEADER, custom, java.util.Arrays.copyOfRange(img, 8, img.length));
        assertEquals(42L, Instance.instantiate(ModuleParser.parse(withTrailingCustom)).invoke("f")[0]);
        assertEquals(42L, Instance.instantiate(ModuleParser.parse(withLeadingCustom)).invoke("f")[0]);
    }

    @Test
    void customSectionNameMustFitAndBeUtf8() {
        // name length 9 but section holds only 3 bytes
        malformed(Bin.cat(HEADER, Bin.bytes(0, 3, 9, 0x61, 0x62)));
        // invalid UTF-8 name (0xC0 0x80 is an overlong NUL)
        malformed(Bin.cat(HEADER, Bin.bytes(0, 3, 2, 0xC0, 0x80)));
    }

    @Test
    void sectionSizeMismatchAndOverrun() {
        // type section declaring size 5 but its vec(0) uses only 1 byte
        ModuleException e = malformed(Bin.cat(HEADER, Bin.bytes(1, 5, 0, 0, 0, 0, 0)));
        assertTrue(e.getMessage().contains("section size mismatch"), e.getMessage());
        // size larger than the rest of the file
        ModuleException e2 = malformed(Bin.cat(HEADER, Bin.bytes(1, 100, 0)));
        assertTrue(e2.getMessage().contains("section size"), e2.getMessage());
    }

    @Test
    void unknownSectionIdAndUnsupportedSections() {
        malformed(Bin.cat(HEADER, Bin.bytes(13, 0)));
        for (int id : new int[] {2, 4, 9, 12}) {
            ModuleException e = invalid(Bin.cat(HEADER, Bin.bytes(id, 1, 0)));
            assertTrue(e.getMessage().contains("unsupported section"), id + " " + e.getMessage());
        }
    }

    @Test
    void hostileCountsDoNotAllocate() {
        // type vec claims 2^32-1 entries with 1 byte left
        ModuleException e = malformed(Bin.cat(HEADER, Bin.bytes(1, 6, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0)));
        assertTrue(e.getMessage().contains("count exceeds remaining"), e.getMessage());
        // function body with locals group count 2^32-1
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.funcRaw(t, Bin.cat(Bin.u(1), Bin.u(0xFFFFFFFFL), Bin.bytes(0x7F), Bin.bytes(0x0B)));
        invalid(m.build());
    }

    @Test
    void tooManyLocalsIsInvalid() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.funcRaw(t, Bin.cat(Bin.u(1), Bin.u(50001), Bin.bytes(0x7F), Bin.bytes(0x0B)));
        assertTrue(invalid(m.build()).getMessage().contains("too many locals"));
        Bin.M ok = new Bin.M();
        int t2 = ok.type(":");
        ok.funcRaw(t2, Bin.cat(Bin.u(1), Bin.u(50000), Bin.bytes(0x7F), Bin.bytes(0x0B)));
        ModuleParser.parse(ok.build());
    }

    @Test
    void functionAndCodeCountsMustAgree() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.func(t, "", Bin.c());
        byte[] img = m.build();
        // drop the code section (last section) entirely
        byte[] noCode = java.util.Arrays.copyOf(img, m.sectionEnds.get(m.sectionEnds.size() - 2));
        ModuleException e = malformed(noCode);
        assertTrue(e.getMessage().contains("inconsistent lengths"), e.getMessage());
    }

    @Test
    void functionBodySizeMustMatchExactly() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        // entry = locals(0) + end + one stray nop after the end
        m.funcRaw(t, Bin.bytes(0, 0x0B, 0x01));
        ModuleException e = malformed(m.build());
        assertTrue(e.getMessage().contains("operators remaining after end of function"), e.getMessage());
        Bin.M m2 = new Bin.M();
        int t2 = m2.type(":");
        m2.funcRaw(t2, Bin.bytes(0, 0x01)); // no end
        malformed(m2.build());
    }

    @Test
    void illegalOpcodeAndValueTypeAreMalformed() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.func(t, "", Bin.c().b(0xFF));
        assertTrue(malformed(m.build()).getMessage().contains("illegal opcode 0xff"));
        Bin.M m2 = new Bin.M();
        m2.types.add(Bin.bytes(0x60, 1, 0x7D, 0)); // f32 param
        assertTrue(malformed(m2.build()).getMessage().contains("unsupported value type"));
        Bin.M m3 = new Bin.M();
        m3.types.add(Bin.bytes(0x61, 0, 0)); // wrong form byte
        malformed(m3.build());
    }

    @Test
    void memoryLimitsValidation() {
        Bin.M m = new Bin.M();
        m.memory = Bin.bytes(1, 0, 0x80, 0x80, 0x04); // min 65536 pages: allowed
        ModuleParser.parse(m.build());
        m.memory = Bin.bytes(1, 0, 0x81, 0x80, 0x04); // 65537
        assertTrue(invalid(m.build()).getMessage().contains("at most 65536 pages"));
        m.memory = Bin.bytes(1, 1, 3, 2); // min 3 > max 2
        assertTrue(invalid(m.build()).getMessage().contains("minimum must not be greater than maximum"));
        m.memory = Bin.bytes(1, 2, 3); // bad flag
        malformed(m.build());
        byte[] two = Bin.cat(HEADER, Bin.bytes(5, 5, 2, 0, 1, 0, 1));
        assertTrue(invalid(two).getMessage().contains("multiple memories"));
    }

    @Test
    void exportValidation() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        int f = m.func(t, "", Bin.c());
        m.exportFunc("a", f).exportFunc("a", f);
        assertTrue(invalid(m.build()).getMessage().contains("duplicate export name"));
        Bin.M m2 = new Bin.M();
        m2.type(":");
        m2.func(0, "", Bin.c());
        m2.exportFunc("x", 5);
        assertTrue(invalid(m2.build()).getMessage().contains("unknown function"));
        Bin.M m3 = new Bin.M();
        m3.export("m", 2, 0);
        assertTrue(invalid(m3.build()).getMessage().contains("unknown memory"));
        Bin.M m4 = new Bin.M();
        m4.export("g", 3, 0);
        assertTrue(invalid(m4.build()).getMessage().contains("unknown global"));
        Bin.M m5 = new Bin.M();
        m5.exports.add(Bin.cat(Bin.u(1), Bin.bytes('t', 1, 0))); // table export
        assertTrue(invalid(m5.build()).getMessage().contains("unsupported export kind"));
        Bin.M m6 = new Bin.M();
        m6.exports.add(Bin.cat(Bin.u(2), Bin.bytes(0xC3, 0x28, 0, 0))); // invalid UTF-8 name
        malformed(m6.build());
    }

    @Test
    void startFunctionMustBeUnitTyped() {
        Bin.M m = new Bin.M();
        int t = m.type(":i");
        int f = m.func(t, "", Bin.c().i32(1));
        m.start(f);
        assertTrue(invalid(m.build()).getMessage().contains("start function must have type [] -> []"));
        Bin.M m2 = new Bin.M();
        m2.start(0);
        assertTrue(invalid(m2.build()).getMessage().contains("unknown function"));
    }

    @Test
    void globalInitializers() {
        Bin.M m = new Bin.M();
        m.globals.add(Bin.cat(Bin.bytes(0x7F, 0, 0x42), Bin.s(1), Bin.bytes(0x0B))); // i32 global with i64.const
        assertTrue(invalid(m.build()).getMessage().contains("constant expression type mismatch"));
        Bin.M m2 = new Bin.M();
        m2.globals.add(Bin.cat(Bin.bytes(0x7F, 0, 0x6A, 0x0B))); // i32.add in const expr
        assertTrue(invalid(m2.build()).getMessage().contains("unsupported constant expression"));
        Bin.M m3 = new Bin.M();
        m3.globals.add(Bin.cat(Bin.bytes(0x7F, 2, 0x41, 0, 0x0B))); // mutability byte 2
        malformed(m3.build());
        Bin.M m4 = new Bin.M();
        m4.globals.add(Bin.cat(Bin.bytes(0x7F, 0, 0x41, 0))); // missing end
        malformed(m4.build());
        Bin.M m5 = new Bin.M();
        m5.globals.add(Bin.cat(Bin.bytes(0x7F, 0, 0x41, 0, 0x0B, 0x0B))); // two ends: size mismatch
        malformed(m5.build());
    }

    @Test
    void dataSegmentsNeedMemoryAndFitAtLinkTime() {
        Bin.M m = new Bin.M();
        m.data(0, new byte[] {1});
        assertTrue(invalid(m.build()).getMessage().contains("unknown memory"));
        Bin.M m2 = new Bin.M();
        m2.mem(1, -1);
        m2.data(65535, new byte[] {1, 2});
        Module parsed = ModuleParser.parse(m2.build());
        ModuleException e = assertThrows(ModuleException.class, () -> Instance.instantiate(parsed));
        assertEquals(ModuleException.Kind.LINK, e.kind());
        assertTrue(e.getMessage().contains("data segment does not fit"), e.getMessage());
        Bin.M m3 = new Bin.M();
        m3.mem(1, -1);
        m3.data(65534, new byte[] {1, 2});
        Instance ok = Instance.instantiate(ModuleParser.parse(m3.build()));
        assertEquals(2, ok.readMemory(65534, 2)[1]);
        // bad segment flag
        Bin.M m4 = new Bin.M();
        m4.mem(1, -1);
        m4.data.add(Bin.bytes(1, 0));
        malformed(m4.build());
    }

    @Test
    void everyTruncationIsRejectedUnlessItIsASectionBoundary() {
        Bin.M m = richModule();
        byte[] img = m.build();
        for (int len = 0; len < img.length; len++) {
            byte[] prefix = java.util.Arrays.copyOf(img, len);
            boolean boundary = m.sectionEnds.contains(len);
            try {
                ModuleParser.parse(prefix);
                if (!boundary) {
                    fail("prefix of length " + len + " of " + img.length + " parsed");
                }
            } catch (ModuleException e) {
                if (!boundary) {
                    assertEquals(ModuleException.Kind.MALFORMED, e.kind(), "len " + len + ": " + e.getMessage());
                }
            }
        }
    }

    @Test
    void everyByteFlipEitherParsesOrThrowsModuleExceptionAndSurvivorsRunSafely() {
        byte[] img = richModule().build();
        int parsedOk = 0;
        int rejected = 0;
        for (int i = 0; i < img.length; i++) {
            for (int mask : new int[] {0x01, 0x80, 0xFF, 0x40}) {
                byte[] b = img.clone();
                b[i] ^= (byte) mask;
                Module m;
                try {
                    m = ModuleParser.parse(b);
                } catch (ModuleException e) {
                    rejected++;
                    continue;
                }
                parsedOk++;
                try {
                    Instance inst = Instance.instantiate(m, 10_000);
                    for (String name : m.exportedFunctions()) {
                        try {
                            long[] args = new long[m.exportArity(name)];
                            inst.invoke(name, args);
                        } catch (Trap expected) {
                            // traps are legitimate outcomes of mutated programs
                        }
                    }
                } catch (ModuleException | Trap expected) {
                    // link errors (e.g. data segment moved out of bounds) and start-function traps are fine
                }
            }
        }
        assertTrue(rejected > 100, "rejected " + rejected);
        assertTrue(parsedOk > 20, "parsedOk " + parsedOk);
    }

    @Test
    void memoryLimitBoundaries() {
        Bin.M m = new Bin.M();
        m.memory = Bin.bytes(1, 1, 1, 0x80, 0x80, 0x04); // min 1, max 65536: allowed
        ModuleParser.parse(m.build());
        m.memory = Bin.bytes(1, 1, 1, 0x81, 0x80, 0x04); // max 65537
        assertTrue(invalid(m.build()).getMessage().contains("at most 65536 pages"));
        m.memory = Bin.bytes(1, 1, 2, 2); // min == max is legal
        ModuleParser.parse(m.build());
    }

    @Test
    void exportIndexBoundaries() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        int f = m.func(t, "", Bin.c());
        m.exportFunc("ok", f);
        ModuleParser.parse(m.build()); // control: the last valid index
        m.exportFunc("bad", f + 1); // one past the last function
        assertTrue(invalid(m.build()).getMessage().contains("unknown function"));
        Bin.M g = new Bin.M();
        g.mem(1, -1);
        g.export("m", 2, 0);
        ModuleParser.parse(g.build());
        g.export("m2", 2, 1); // only memory index 0 exists
        assertTrue(invalid(g.build()).getMessage().contains("unknown memory"));
        Bin.M gl = new Bin.M();
        int gi = gl.global('i', false, 1);
        gl.export("g", 3, gi);
        ModuleParser.parse(gl.build());
        gl.export("g2", 3, gi + 1);
        assertTrue(invalid(gl.build()).getMessage().contains("unknown global"));
    }

    @Test
    void startFunctionWithParametersIsInvalid() {
        Bin.M m = new Bin.M();
        int t = m.type("i:");
        int f = m.func(t, "", Bin.c());
        m.start(f);
        assertTrue(invalid(m.build()).getMessage().contains("start function must have type [] -> []"));
        Bin.M ok = new Bin.M();
        int t0 = ok.type(":");
        ok.start(ok.func(t0, "", Bin.c()));
        ModuleParser.parse(ok.build());
    }

    @Test
    void constantExpressionMustEndWithEndMarker() {
        Bin.M m = new Bin.M();
        m.globals.add(Bin.bytes(0x7F, 0, 0x41, 0, 0x0C)); // ends with br instead of end
        ModuleException e = malformed(m.build());
        assertTrue(e.getMessage().contains("missing its end marker"), e.getMessage());
        Bin.M ok = new Bin.M();
        ok.globals.add(Bin.bytes(0x7F, 0, 0x41, 0, 0x0B));
        ModuleParser.parse(ok.build());
    }

    @Test
    void multipleMemoryDeclarationsFailBeforeSizeChecks() {
        // count 2 with two well-formed entries: must be INVALID "multiple memories", not a size mismatch
        byte[] two = Bin.cat(HEADER, Bin.bytes(5, 5, 2, 0, 1, 0, 1));
        assertTrue(invalid(two).getMessage().contains("multiple memories"));
    }

    @Test
    void functionBodySizeBeyondTheCodeSectionIsMalformed() {
        // two sections: code (body claims one byte more than the section holds) followed by data
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.funcTypes.add(t);
        m.mem(1, -1);
        m.codes.add(Bin.bytes(0, 0x0B));
        m.data(0, new byte[] {1});
        byte[] ok = m.build();
        ModuleParser.parse(ok); // control
        // locate the code section: id 0x0A, then size, count 1, body size 2. Bump the body size to 3.
        int at = -1;
        for (int i = 8; i + 4 < ok.length; i++) {
            if (ok[i] == 0x0A && ok[i + 1] == 4 && ok[i + 2] == 1 && ok[i + 3] == 2) {
                at = i + 3;
                break;
            }
        }
        assertTrue(at > 0, "code section not found");
        byte[] bad = ok.clone();
        bad[at] = 3;
        ModuleException e = malformed(bad);
        assertTrue(e.getMessage().contains("function body size exceeds remaining bytes"), e.getMessage());
    }
}
