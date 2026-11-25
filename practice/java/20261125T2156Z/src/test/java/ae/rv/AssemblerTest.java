package ae.rv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class AssemblerTest {
    private static int[] asm(String s) {
        return Assembler.assemble(s).words();
    }

    @Test
    void basicInstructionsMatchLiteralWords() {
        assertArrayEquals(new int[] {0x00100093}, asm("addi x1, x0, 1"));
        assertArrayEquals(new int[] {0x002081B3}, asm("add gp, ra, sp"));
        assertArrayEquals(new int[] {0x00812283}, asm("lw t0, 8(sp)"));
        assertArrayEquals(new int[] {0x00512423}, asm("sw t0, 8(sp)"));
        assertArrayEquals(new int[] {0x00000073}, asm("ecall"));
        assertArrayEquals(new int[] {0x00008067}, asm("ret"));
        assertArrayEquals(new int[] {0x00000013}, asm("nop"));
    }

    @Test
    void abiNames() {
        assertArrayEquals(asm("add x10, x11, x12"), asm("add a0, a1, a2"));
        assertArrayEquals(asm("add x8, x9, x18"), asm("add fp, s1, s2"));
        assertArrayEquals(asm("add x8, x9, x18"), asm("add s0, s1, s2"));
        assertArrayEquals(asm("add x0, x5, x31"), asm("add zero, t0, t6"));
        assertArrayEquals(asm("add x28, x29, x30"), asm("add t3, t4, t5"));
        assertArrayEquals(asm("add x3, x4, x27"), asm("add gp, tp, s11"));
    }

    @Test
    void labelsAndBackwardForwardBranches() {
        Program p = Assembler.assemble("""
            start: beq x0, x0, end   # forward 8
                   nop
            end:   bne x1, x2, start # backward -8
            """);
        assertEquals(0, p.labels().get("start"));
        assertEquals(8, p.labels().get("end"));
        assertEquals(Codec.encode(new Instr(Op.BEQ, 0, 0, 0, 8)), p.words()[0]);
        assertEquals(Codec.encode(new Instr(Op.BNE, 0, 1, 2, -8)), p.words()[2]);
    }

    @Test
    void numericBranchOffsetsAndLabelsOnOwnLine() {
        Program p = Assembler.assemble("a:\n  b:\n  beq x1, x2, -4\n j a\n");
        assertEquals(0, p.labels().get("a"));
        assertEquals(0, p.labels().get("b"));
        assertEquals(Codec.encode(new Instr(Op.BEQ, 0, 1, 2, -4)), p.words()[0]);
        assertEquals(Codec.encode(new Instr(Op.JAL, 0, 0, 0, -4)), p.words()[1]);
    }

    @Test
    void pseudoInstructions() {
        assertArrayEquals(asm("addi x5, x6, 0"), asm("mv x5, x6"));
        assertArrayEquals(asm("xori x5, x6, -1"), asm("not x5, x6"));
        assertArrayEquals(asm("sub x5, x0, x6"), asm("neg x5, x6"));
        assertArrayEquals(asm("sltiu x5, x6, 1"), asm("seqz x5, x6"));
        assertArrayEquals(asm("sltu x5, x0, x6"), asm("snez x5, x6"));
        assertArrayEquals(asm("jalr x0, 0(x7)"), asm("jr x7"));
        assertArrayEquals(new int[] {asm("jal x1, 8")[0], asm("nop")[0], asm("nop")[0]},
                asm("jal L\n nop\n L: nop"));
        assertArrayEquals(asm("beq x5, x0, 4"), asm("beqz x5, 4"));
        assertArrayEquals(asm("bne x5, x0, 4"), asm("bnez x5, 4"));
        assertArrayEquals(asm("blt x5, x0, 4"), asm("bltz x5, 4"));
        assertArrayEquals(asm("bge x5, x0, 4"), asm("bgez x5, 4"));
        assertArrayEquals(asm("blt x6, x5, 4"), asm("bgt x5, x6, 4"));
        assertArrayEquals(asm("bge x6, x5, 4"), asm("ble x5, x6, 4"));
        assertArrayEquals(asm("bltu x6, x5, 4"), asm("bgtu x5, x6, 4"));
        assertArrayEquals(asm("bgeu x6, x5, 4"), asm("bleu x5, x6, 4"));
    }

    @Test
    void liExpansions() {
        assertArrayEquals(asm("addi x1, x0, 2047"), asm("li x1, 2047"));
        assertArrayEquals(asm("addi x1, x0, -2048"), asm("li x1, -2048"));
        assertEquals(2, asm("li x1, 2048").length);
        assertArrayEquals(new int[] {0x000010B7, 0x80008093}, asm("li x1, 2048")); // lui 1; addi -2048
        assertArrayEquals(asm("lui x1, 0x12345"), asm("li x1, 0x12345000"));      // no addi when low bits are 0
        assertEquals(1, asm("li x1, 0x12345000").length);
        assertArrayEquals(asm("lui x1, 0x12346\n addi x1, x1, -1"), asm("li x1, 0x12345FFF"));
        assertArrayEquals(asm("lui x1, 0x80000"), asm("li x1, -2147483648"));
        assertArrayEquals(asm("lui x1, 0x80000"), asm("li x1, 0x80000000"));
        assertArrayEquals(asm("addi x1, x0, 0"), asm("li x1, 0"));
    }

    @Test
    void wordDirective() {
        Program p = Assembler.assemble("data: .word 0xDEADBEEF\n .word -1\n .word 4294967295");
        assertArrayEquals(new int[] {0xDEADBEEF, -1, -1}, p.words());
    }

    @Test
    void liAdvancesLabelAddressesByItsSize() {
        Program p = Assembler.assemble("li x1, 0x12345FFF\n here: nop\n li x2, 5\n there: nop");
        assertEquals(8, p.labels().get("here"));
        assertEquals(16, p.labels().get("there"));
    }

    private static void assertAsmError(String src, String expectedPrefix) {
        AsmException e = assertThrows(AsmException.class, () -> Assembler.assemble(src), src);
        assertTrue(e.getMessage().startsWith(expectedPrefix), e.getMessage());
    }

    @Test
    void errorsCarryLineNumbers() {
        assertAsmError("nop\nfrob x1, x2", "line 2:");
        assertAsmError("a: nop\na: nop", "line 2:");
        assertAsmError("add x1, x2", "line 1:");
        assertAsmError("nop\n\naddi x1, x2, 4096", "line 3:");
        assertAsmError("beq x1, x2, nowhere", "line 1:");
        assertAsmError("add x1, x2, x32", "line 1:");
        assertAsmError("lw x1, 8", "line 1:");
        assertAsmError("li x1, 4294967296", "line 1:");
        assertAsmError("li x1, -2147483649", "line 1:");
        assertAsmError("lui x1, 0x100000", "line 1:");
        assertAsmError("slli x1, x2, 32", "line 1:");
        assertAsmError("beq x1, x2, 3", "line 1:");
    }

    @Test
    void errorsAreSpecific() {
        assertTrue(assertThrows(AsmException.class, () -> Assembler.assemble("frob x1")).getMessage().contains("frob"));
        assertTrue(assertThrows(AsmException.class, () -> Assembler.assemble("j gone")).getMessage().contains("gone"));
        assertTrue(assertThrows(AsmException.class, () -> Assembler.assemble("a: nop\na: nop")).getMessage().contains("a"));
    }

    @Test
    void branchOutOfRangeIsRejected() {
        StringBuilder sb = new StringBuilder("beq x0, x0, far\n");
        for (int n = 0; n < 1100; n++) sb.append("nop\n"); // 4400 bytes
        sb.append("far: nop\n");
        assertAsmError(sb.toString(), "line 1:");
        // control: 1022 nops -> offset 4092 fits
        StringBuilder ok = new StringBuilder("beq x0, x0, far\n");
        for (int n = 0; n < 1022; n++) ok.append("nop\n");
        ok.append("far: nop\n");
        assertEquals(4092, Codec.decode(asm(ok.toString())[0]).imm());
    }

    /** Disassemble with toString and assemble again: must give the same word. */
    @Test
    void disassembleAssembleRoundTrip() {
        Random r = new Random(5);
        int checked = 0;
        for (int n = 0; n < 20_000; n++) {
            int w = r.nextInt();
            Instr in;
            try {
                in = Codec.decode(w);
            } catch (IllegalInstructionException e) {
                continue;
            }
            assertArrayEquals(new int[] {w}, asm(in.toString()), in.toString());
            checked++;
        }
        assertTrue(checked > 100, "checked " + checked);
        // random words rarely hit some formats; sweep every op explicitly too
        for (Op op : Op.values()) {
            Instr in = switch (op.format) {
                case R -> new Instr(op, 31, 30, 29, 0);
                case I -> new Instr(op, 31, 30, 0, -5);
                case SH -> new Instr(op, 31, 30, 0, 31);
                case S -> new Instr(op, 0, 30, 29, -2048);
                case B -> new Instr(op, 0, 30, 29, -4096);
                case U -> new Instr(op, 31, 0, 0, 0xFFFFF);
                case J -> new Instr(op, 31, 0, 0, -(1 << 20));
                case SYS -> new Instr(op, 0, 0, 0, 0);
            };
            assertArrayEquals(new int[] {Codec.encode(in)}, asm(in.toString()), in.toString());
        }
    }
}
