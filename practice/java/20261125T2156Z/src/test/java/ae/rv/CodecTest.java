package ae.rv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CodecTest {
    private static Instr i(Op op, int rd, int rs1, int rs2, int imm) {
        return new Instr(op, rd, rs1, rs2, imm);
    }

    @Test
    void literalEncodings() {
        assertEquals(0x00100093, Codec.encode(i(Op.ADDI, 1, 0, 0, 1)));
        assertEquals(0x002081B3, Codec.encode(i(Op.ADD, 3, 1, 2, 0)));
        assertEquals(0x402081B3, Codec.encode(i(Op.SUB, 3, 1, 2, 0)));
        assertEquals(0x022081B3, Codec.encode(i(Op.MUL, 3, 1, 2, 0)));
        assertEquals(0x00812283, Codec.encode(i(Op.LW, 5, 2, 0, 8)));
        assertEquals(0x00512423, Codec.encode(i(Op.SW, 0, 2, 5, 8)));
        assertEquals(0xFE110FA3, Codec.encode(i(Op.SB, 0, 2, 1, -1)));
        assertEquals(0x00208463, Codec.encode(i(Op.BEQ, 0, 1, 2, 8)));
        assertEquals(0xFE000EE3, Codec.encode(i(Op.BEQ, 0, 0, 0, -4)));
        assertEquals(0x008000EF, Codec.encode(i(Op.JAL, 1, 0, 0, 8)));
        assertEquals(0x123450B7, Codec.encode(i(Op.LUI, 1, 0, 0, 0x12345)));
        assertEquals(0xFFFFF0B7, Codec.encode(i(Op.LUI, 1, 0, 0, 0xFFFFF)));
        assertEquals(0x40315093, Codec.encode(i(Op.SRAI, 1, 2, 0, 3)));
        assertEquals(0x00008067, Codec.encode(i(Op.JALR, 0, 1, 0, 0)));
        assertEquals(0x00000073, Codec.encode(i(Op.ECALL, 0, 0, 0, 0)));
        assertEquals(0x00100073, Codec.encode(i(Op.EBREAK, 0, 0, 0, 0)));
    }

    @Test
    void literalDecodings() {
        assertEquals(i(Op.ADDI, 1, 0, 0, 1), Codec.decode(0x00100093));
        assertEquals(i(Op.BEQ, 0, 0, 0, -4), Codec.decode(0xFE000EE3));
        assertEquals(i(Op.SB, 0, 2, 1, -1), Codec.decode(0xFE110FA3));
        assertEquals(i(Op.SRAI, 1, 2, 0, 3), Codec.decode(0x40315093));
        assertEquals(i(Op.LUI, 1, 0, 0, 0xFFFFF), Codec.decode(0xFFFFF0B7));
    }

    private static Instr random(Random r, Op op) {
        int rd = r.nextInt(32), rs1 = r.nextInt(32), rs2 = r.nextInt(32);
        return switch (op.format) {
            case R -> i(op, rd, rs1, rs2, 0);
            case I -> i(op, rd, rs1, 0, r.nextInt(4096) - 2048);
            case SH -> i(op, rd, rs1, 0, r.nextInt(32));
            case S -> i(op, 0, rs1, rs2, r.nextInt(4096) - 2048);
            case B -> i(op, 0, rs1, rs2, (r.nextInt(4096) - 2048) * 2);
            case U -> i(op, rd, 0, 0, r.nextInt(1 << 20));
            case J -> i(op, rd, 0, 0, (r.nextInt(1 << 20) - (1 << 19)) * 2);
            case SYS -> i(op, 0, 0, 0, 0);
        };
    }

    @Test
    void randomInstructionsRoundTrip() {
        Random r = new Random(7);
        for (Op op : Op.values()) {
            for (int n = 0; n < 500; n++) {
                Instr in = random(r, op);
                assertEquals(in, Codec.decode(Codec.encode(in)), in.toString());
            }
        }
    }

    @Test
    void extremeImmediatesRoundTrip() {
        for (Instr in : new Instr[] {
            i(Op.ADDI, 1, 1, 0, -2048), i(Op.ADDI, 1, 1, 0, 2047),
            i(Op.SW, 0, 1, 1, -2048), i(Op.SW, 0, 1, 1, 2047),
            i(Op.BNE, 0, 1, 1, -4096), i(Op.BNE, 0, 1, 1, 4094),
            i(Op.JAL, 1, 0, 0, -(1 << 20)), i(Op.JAL, 1, 0, 0, (1 << 20) - 2)}) {
            assertEquals(in, Codec.decode(Codec.encode(in)), in.toString());
        }
    }

    @Test
    void randomWordsAreRejectedOrCanonical() {
        Random r = new Random(11);
        int accepted = 0;
        for (int n = 0; n < 200_000; n++) {
            int w = r.nextInt();
            try {
                Instr in = Codec.decode(w);
                accepted++;
                assertEquals(w, Codec.encode(in), Integer.toHexString(w));
            } catch (IllegalInstructionException e) {
                // fine
            }
        }
        assertTrue(accepted > 1000, "accepted " + accepted);
        assertTrue(accepted < 190_000, "accepted " + accepted);
    }

    @Test
    void nonCanonicalWordsRejected() {
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0));
        // add with funct7 = 0x10 (not 0, 0x20 or 1)
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0x202081B3));
        // control: funct7 = 0 is add
        assertEquals(Op.ADD, Codec.decode(0x002081B3).op());
        // slli with funct7 = 0x20
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0x40311093));
        // ecall with rd = 1
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0x000000F3));
        // jalr with funct3 = 1
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0x00009067));
        // fence
        assertThrows(IllegalInstructionException.class, () -> Codec.decode(0x0000000F));
    }

    @Test
    void instrValidation() {
        assertThrows(IllegalArgumentException.class, () -> i(Op.ADDI, 1, 1, 0, 2048));
        assertThrows(IllegalArgumentException.class, () -> i(Op.ADDI, 32, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> i(Op.BEQ, 0, 1, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> i(Op.BEQ, 0, 1, 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> i(Op.JAL, 1, 0, 0, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> i(Op.SLLI, 1, 1, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> i(Op.LUI, 1, 0, 0, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> i(Op.ADD, 1, 1, 1, 5)); // non-canonical imm
        assertThrows(IllegalArgumentException.class, () -> i(Op.SW, 3, 1, 1, 0)); // non-canonical rd
    }

    @Test
    void toStringUsesAssemblerSyntax() {
        assertEquals("add x3, x1, x2", i(Op.ADD, 3, 1, 2, 0).toString());
        assertEquals("lw x5, 8(x2)", i(Op.LW, 5, 2, 0, 8).toString());
        assertEquals("sw x5, -4(x2)", i(Op.SW, 0, 2, 5, -4).toString());
        assertEquals("beq x1, x2, -8", i(Op.BEQ, 0, 1, 2, -8).toString());
        assertEquals("lui x1, 0x12345", i(Op.LUI, 1, 0, 0, 0x12345).toString());
        assertEquals("ecall", i(Op.ECALL, 0, 0, 0, 0).toString());
        assertEquals("jal x1, 8", i(Op.JAL, 1, 0, 0, 8).toString());
    }
}
