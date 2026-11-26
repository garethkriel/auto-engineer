package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DisassemblerTest {
    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s.replace(" ", ""));
    }

    private static List<Instruction> dec(String hex) {
        return Disassembler.decode(hex(hex));
    }

    private static String rendered(String hex) {
        return Disassembler.decode(hex(hex)).stream().map(Disassembler::render).toList().toString();
    }

    @Test
    void opcodeTableAnchors() {
        assertEquals(202, Opcodes.COUNT);
        assertEquals("nop", Opcodes.name(0x00));
        assertEquals("bipush", Opcodes.name(0x10));
        assertEquals("iload", Opcodes.name(0x15));
        assertEquals("iload_0", Opcodes.name(0x1a));
        assertEquals("aload_3", Opcodes.name(0x2d));
        assertEquals("istore", Opcodes.name(0x36));
        assertEquals("pop", Opcodes.name(0x57));
        assertEquals("iadd", Opcodes.name(0x60));
        assertEquals("iinc", Opcodes.name(0x84));
        assertEquals("ifeq", Opcodes.name(0x99));
        assertEquals("goto", Opcodes.name(0xa7));
        assertEquals("tableswitch", Opcodes.name(0xaa));
        assertEquals("getstatic", Opcodes.name(0xb2));
        assertEquals("invokeinterface", Opcodes.name(0xb9));
        assertEquals("invokedynamic", Opcodes.name(0xba));
        assertEquals("wide", Opcodes.name(0xc4));
        assertEquals("jsr_w", Opcodes.name(0xc9));
    }

    @ParameterizedTest
    @ValueSource(ints = {0xca, 0xcb, 0xd0, 0xfd, 0xfe, 0xff})
    void undefinedOpcodesRejected(int op) {
        assertThrows(ClassFormatException.class, () -> Disassembler.decode(new byte[] {(byte) op}));
        assertThrows(ClassFormatException.class, () -> Opcodes.name(op));
    }

    @Test
    void simpleSequenceOffsets() {
        List<Instruction> ins = dec("04 3c 1b ac");
        assertEquals(4, ins.size());
        assertEquals("iconst_1", ins.get(0).mnemonic());
        assertEquals("istore_1", ins.get(1).mnemonic());
        assertEquals(1, ins.get(1).operand());
        assertEquals(3, ins.get(3).offset());
        assertEquals("ireturn", ins.get(3).mnemonic());
        assertEquals("[iconst_1, istore_1, iload_1, ireturn]", rendered("04 3c 1b ac"));
    }

    @Test
    void emptyCodeDecodesToNothing() {
        assertTrue(Disassembler.decode(new byte[0]).isEmpty());
    }

    @Test
    void operandForms() {
        assertEquals("[bipush -1]", rendered("10 ff"));
        assertEquals("[bipush 127]", rendered("10 7f"));
        assertEquals("[sipush -32768]", rendered("11 80 00"));
        assertEquals("[ldc #3]", rendered("12 03"));
        assertEquals("[ldc_w #258]", rendered("13 01 02"));
        assertEquals("[ldc2_w #258]", rendered("14 01 02"));
        assertEquals("[iload 200]", rendered("15 c8"));
        assertEquals("[astore 4]", rendered("3a 04"));
        assertEquals("[iinc 1, -1]", rendered("84 01 ff"));
        assertEquals("[getstatic #65535]", rendered("b2 ff ff"));
        assertEquals("[invokeinterface #7, 2]", rendered("b9 00 07 02 00"));
        assertEquals("[invokedynamic #9, 0]", rendered("ba 00 09 00 00"));
        assertEquals("[multianewarray #3, 2]", rendered("c5 00 03 02"));
        assertEquals("[new #1, anewarray #2, checkcast #3, instanceof #4]", rendered("bb 00 01 bd 00 02 c0 00 03 c1 00 04"));
        assertEquals("[ret 5]", rendered("a9 05"));
    }

    @Test
    void newarrayTypeNames() {
        String[] names = {"boolean", "char", "float", "double", "byte", "short", "int", "long"};
        for (int t = 4; t <= 11; t++) {
            assertEquals("[newarray " + names[t - 4] + "]", rendered("bc " + String.format("%02x", t)), "atype " + t);
        }
        assertThrows(ClassFormatException.class, () -> dec("bc 03"));
        assertThrows(ClassFormatException.class, () -> dec("bc 0c"));
        assertThrows(ClassFormatException.class, () -> dec("bc 00"));
    }

    @Test
    void forwardAndBackwardBranches() {
        // 0 iconst_0; 1 ifeq +5 -> 6; 4 nop; 5 nop; 6 return
        List<Instruction> ins = dec("03 99 00 05 00 00 b1");
        assertEquals(5, ins.size());
        assertArrayEquals(new int[] {6}, ins.get(1).targets());
        assertEquals(3, ins.get(1).length());
        Disassembler.validateTargets(ins, 7);
        // 0 nop; 1 goto -1 -> 0
        List<Instruction> back = dec("00 a7 ff ff");
        assertArrayEquals(new int[] {0}, back.get(1).targets());
        assertEquals("[nop, goto 0]", Disassembler.decode(hex("00 a7 ff ff")).stream().map(Disassembler::render).toList().toString());
        Disassembler.validateTargets(back, 4);
        // goto_w with a 4-byte offset: 0 goto_w +6 -> 6; 5 nop; 6 return
        List<Instruction> wide = dec("c8 00 00 00 06 00 b1");
        assertEquals(5, wide.get(0).length());
        assertArrayEquals(new int[] {6}, wide.get(0).targets());
        Disassembler.validateTargets(wide, 7);
    }

    @Test
    void wideForms() {
        List<Instruction> ins = dec("c4 15 01 2c c4 84 01 2c 03 e8 c4 a9 00 07");
        assertEquals(3, ins.size());
        assertEquals(4, ins.get(0).length());
        assertEquals(300, ins.get(0).operand());
        assertEquals(6, ins.get(1).length());
        assertEquals(300, ins.get(1).operand());
        assertEquals(1000, ins.get(1).operand2());
        assertEquals(4, ins.get(2).length());
        assertEquals("[iload_w 300, iinc_w 300, 1000, ret_w 7]", rendered("c4 15 01 2c c4 84 01 2c 03 e8 c4 a9 00 07"));
        assertEquals("[istore_w 65535]", rendered("c4 36 ff ff"));
        assertEquals("[iinc_w 0, -2]", rendered("c4 84 00 00 ff fe"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"c4 00 00 00", "c4 b1 00 00", "c4 10 00 01", "c4 99 00 00", "c4 c4 15 01 2c"})
    void wideOnlyPrefixesLoadsStoresIincRet(String h) {
        assertThrows(ClassFormatException.class, () -> dec(h), h);
    }

    @ParameterizedTest
    @ValueSource(strings = {"11 01", "13 01", "99 00", "a7 00", "c8 00 00 00", "b9 00 07 02", "ba 00 09 00", "c5 00 03",
        "c4", "c4 15 01", "c4 84 01 2c 03", "10", "84 01", "aa 00 00", "ab 00"})
    void truncatedInstructionsRejected(String h) {
        assertThrows(ClassFormatException.class, () -> dec(h), h);
    }

    @ParameterizedTest
    @ValueSource(strings = {"b9 00 07 02 01", "b9 00 07 00 00", "ba 00 09 00 01", "ba 00 09 01 00", "c5 00 03 00"})
    void invokeInterfaceIndyMultianewarrayOperandChecks(String h) {
        assertThrows(ClassFormatException.class, () -> dec(h), h);
    }

    /** Tail shared by the switch tests, at absolute offset 24: iconst_0 ireturn iconst_1 ireturn iconst_2 ireturn. */
    private static final String TAIL = "03 ac 04 ac 05 ac";

    private static String tableswitchAt(int nops) {
        // padding so the default operand starts at a multiple of 4 (offset 4)
        int opcodeAt = nops;
        int pad = (4 - (opcodeAt + 1) % 4) % 4;
        int def = 24 - opcodeAt;
        int k1 = 26 - opcodeAt;
        int k2 = 28 - opcodeAt;
        return "00 ".repeat(nops) + "aa " + "00 ".repeat(pad)
            + String.format("%08x %08x %08x %08x %08x ", def, 1, 2, k1, k2) + TAIL;
    }

    @ParameterizedTest
    @CsvSource({"0,24", "1,23", "2,22", "3,21"})
    void tableswitchPaddingFollowsOffset(int nops, int length) {
        List<Instruction> ins = dec(tableswitchAt(nops));
        Instruction sw = ins.get(nops);
        assertEquals("tableswitch", sw.mnemonic());
        assertEquals(nops, sw.offset());
        assertEquals(length, sw.length(), "length with " + nops + " leading nops");
        assertArrayEquals(new int[] {24, 26, 28}, sw.targets());
        assertEquals(1, sw.operand());
        assertEquals(2, sw.operand2());
        assertEquals("tableswitch {1: 26, 2: 28, default: 24}", Disassembler.render(sw));
        assertEquals(nops + 1 + 6, ins.size());
        assertEquals(24, ins.get(nops + 1).offset());
        Disassembler.validateTargets(ins, 30);
    }

    @Test
    void tableswitchLiteralBytes() {
        // offset 0 -> 3 padding bytes; default +24, low 1, high 2, +26, +28
        String h = "aa 00 00 00  00 00 00 18  00 00 00 01  00 00 00 02  00 00 00 1a  00 00 00 1c  " + TAIL;
        assertArrayEquals(hex(tableswitchAt(0)), hex(h));
        assertEquals(7, dec(h).size());
    }

    @Test
    void tableswitchWithSingleEntryAndNegativeLow() {
        // 0 tableswitch default +16 low -1 high -1 target +16; 20: return? -> length 20, tail at 20
        String h = "aa 00 00 00  00 00 00 14  ff ff ff ff  ff ff ff ff  00 00 00 14  b1";
        List<Instruction> ins = dec(h);
        assertEquals("tableswitch {-1: 20, default: 20}", Disassembler.render(ins.get(0)));
        assertEquals(20, ins.get(0).length());
        Disassembler.validateTargets(ins, 21);
    }

    @Test
    void lookupswitch() {
        // 0 lookupswitch default +28, 2 pairs: -5 -> +30, 9 -> +32; tail at 28
        String h = "ab 00 00 00  00 00 00 1c  00 00 00 02  ff ff ff fb 00 00 00 1e  00 00 00 09 00 00 00 20  " + TAIL;
        List<Instruction> ins = dec(h);
        Instruction sw = ins.get(0);
        assertEquals(28, sw.length());
        assertArrayEquals(new int[] {-5, 9}, sw.keys());
        assertArrayEquals(new int[] {28, 30, 32}, sw.targets());
        assertEquals("lookupswitch {-5: 30, 9: 32, default: 28}", Disassembler.render(sw));
        Disassembler.validateTargets(ins, 34);
        // same switch after one nop: padding shrinks to 2 and every offset is relative to byte 1
        String h1 = "00 ab 00 00  00 00 00 1b  00 00 00 02  ff ff ff fb 00 00 00 1d  00 00 00 09 00 00 00 1f  " + TAIL;
        List<Instruction> ins1 = dec(h1);
        assertEquals(27, ins1.get(1).length());
        assertArrayEquals(new int[] {28, 30, 32}, ins1.get(1).targets());
    }

    @Test
    void lookupswitchKeysMustBeStrictlyAscending() {
        String dup = "ab 00 00 00  00 00 00 1c  00 00 00 02  00 00 00 09 00 00 00 1e  00 00 00 09 00 00 00 20  " + TAIL;
        String down = "ab 00 00 00  00 00 00 1c  00 00 00 02  00 00 00 09 00 00 00 1e  00 00 00 08 00 00 00 20  " + TAIL;
        assertThrows(ClassFormatException.class, () -> dec(dup));
        assertThrows(ClassFormatException.class, () -> dec(down));
        String ok = down.replace("00 00 00 08", "00 00 00 0a");
        assertEquals(7, dec(ok).size());
    }

    @Test
    void switchStructuralDefects() {
        String lowHigh = "aa 00 00 00  00 00 00 18  00 00 00 03  00 00 00 02  00 00 00 1a  00 00 00 1c  " + TAIL;
        assertThrows(ClassFormatException.class, () -> dec(lowHigh), "low > high");
        String negPairs = "ab 00 00 00  00 00 00 1c  ff ff ff ff  " + TAIL;
        assertThrows(ClassFormatException.class, () -> dec(negPairs), "negative npairs");
        // a table claiming 2^31-1 entries must be rejected by a size check, not by running out of memory
        String huge = "aa 00 00 00  00 00 00 18  80 00 00 00  7f ff ff ff  " + TAIL;
        assertThrows(ClassFormatException.class, () -> dec(huge), "huge table");
        String hugePairs = "ab 00 00 00  00 00 00 18  7f ff ff ff  " + TAIL;
        assertThrows(ClassFormatException.class, () -> dec(hugePairs), "huge pairs");
    }

    @Test
    void switchPaddingContentIsNotChecked() {
        String h = tableswitchAt(0).replaceFirst("aa 00 00 00", "aa 01 02 03");
        assertEquals(7, dec(h).size());
    }

    @Test
    void branchTargetInsideAnInstructionIsRejected() {
        // 0 iconst_0; 1 ifeq +5 -> 6; 4 sipush (4..6); 7 return: target 6 is mid-instruction
        List<Instruction> bad = dec("03 99 00 05 11 00 07 b1");
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(bad, 8));
        // control: same image but target +6 -> 7 (the return)
        List<Instruction> ok = dec("03 99 00 06 11 00 07 b1");
        Disassembler.validateTargets(ok, 8);
    }

    @Test
    void branchTargetRangeIsExact() {
        // goto +3 from offset 0 targets 3 == code length: out of range
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(dec("a7 00 03"), 3));
        // control: one more byte and the target is a real instruction
        Disassembler.validateTargets(dec("a7 00 03 b1"), 4);
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(dec("00 a7 ff f0"), 4));
        // target exactly 0 is fine (checked above) and -1 is not
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(dec("a7 ff ff"), 3));
    }

    @Test
    void switchTargetsAreValidatedToo() {
        // 0 lookupswitch (no pairs) -> length 12; 12 sipush (12..14); 15 return. Default 13 is mid-instruction.
        String body = "ab 00 00 00  00 00 00 0d  00 00 00 00  11 00 07 b1";
        List<Instruction> ins = dec(body);
        assertEquals(12, ins.get(0).length());
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(ins, 16));
        Disassembler.validateTargets(dec(body.replace("00 00 00 0d", "00 00 00 0f")), 16);
        String badKey = "ab 00 00 00  00 00 00 14  00 00 00 01  00 00 00 05 00 00 7f 00  b1";
        assertThrows(ClassFormatException.class, () -> Disassembler.validateTargets(dec(badKey), 21));
        Disassembler.validateTargets(dec(badKey.replace("00 00 7f 00", "00 00 00 14")), 21);
    }
}
