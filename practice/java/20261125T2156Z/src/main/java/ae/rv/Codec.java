package ae.rv;

/** Binary encoding and decoding of {@link Instr}. */
public final class Codec {
    private static final int ECALL_WORD = 0x00000073;
    private static final int EBREAK_WORD = 0x00100073;

    private Codec() { }

    /** Encodes an instruction as a 32-bit word. */
    public static int encode(Instr in) {
        Op op = in.op();
        int rd = in.rd() << 7;
        int f3 = op.funct3 << 12;
        int rs1 = in.rs1() << 15;
        int rs2 = in.rs2() << 20;
        int f7 = op.funct7 << 25;
        int imm = in.imm();
        int opc = op.opcode;
        return switch (op.format) {
            case R -> f7 | rs2 | rs1 | f3 | rd | opc;
            case I -> ((imm & 0xFFF) << 20) | rs1 | f3 | rd | opc;
            case SH -> f7 | (imm << 20) | rs1 | f3 | rd | opc;
            case S -> (((imm >> 5) & 0x7F) << 25) | rs2 | rs1 | f3 | ((imm & 0x1F) << 7) | opc;
            case B -> (((imm >> 12) & 1) << 31) | (((imm >> 5) & 0x3F) << 25) | rs2 | rs1 | f3
                    | (((imm >> 1) & 0xF) << 8) | (((imm >> 11) & 1) << 7) | opc;
            case U -> (imm << 12) | rd | opc;
            case J -> (((imm >> 20) & 1) << 31) | (((imm >> 1) & 0x3FF) << 21) | (((imm >> 11) & 1) << 20)
                    | (((imm >> 12) & 0xFF) << 12) | rd | opc;
            case SYS -> op == Op.ECALL ? ECALL_WORD : EBREAK_WORD;
        };
    }

    /** Decodes a word; throws {@link IllegalInstructionException} if it is not a supported canonical instruction. */
    public static Instr decode(int w) {
        int opc = w & 0x7F;
        int rd = (w >> 7) & 31;
        int f3 = (w >> 12) & 7;
        int rs1 = (w >> 15) & 31;
        int rs2 = (w >> 20) & 31;
        int f7 = w >>> 25;
        for (Op op : Op.values()) {
            if (op.opcode != opc) {
                continue;
            }
            switch (op.format) {
                case R:
                    if (op.funct3 == f3 && op.funct7 == f7) {
                        return new Instr(op, rd, rs1, rs2, 0);
                    }
                    break;
                case I:
                    if (op.funct3 == f3) {
                        return new Instr(op, rd, rs1, 0, w >> 20);
                    }
                    break;
                case SH:
                    if (op.funct3 == f3 && op.funct7 == f7) {
                        return new Instr(op, rd, rs1, 0, rs2);
                    }
                    break;
                case S:
                    if (op.funct3 == f3) {
                        return new Instr(op, 0, rs1, rs2, ((w >> 25) << 5) | ((w >> 7) & 0x1F));
                    }
                    break;
                case B:
                    if (op.funct3 == f3) {
                        int imm = ((w >> 31) << 12) | (((w >> 7) & 1) << 11) | (((w >> 25) & 0x3F) << 5)
                                | (((w >> 8) & 0xF) << 1);
                        return new Instr(op, 0, rs1, rs2, imm);
                    }
                    break;
                case U:
                    return new Instr(op, rd, 0, 0, w >>> 12);
                case J: {
                    int imm = ((w >> 31) << 20) | (((w >> 12) & 0xFF) << 12) | (((w >> 20) & 1) << 11)
                            | (((w >> 21) & 0x3FF) << 1);
                    return new Instr(op, rd, 0, 0, imm);
                }
                case SYS:
                    if (w == ECALL_WORD && op == Op.ECALL || w == EBREAK_WORD && op == Op.EBREAK) {
                        return new Instr(op, 0, 0, 0, 0);
                    }
                    break;
                default:
                    break;
            }
        }
        throw new IllegalInstructionException(w);
    }
}
