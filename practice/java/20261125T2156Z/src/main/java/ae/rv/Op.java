package ae.rv;

import java.util.Locale;

/** Every supported instruction (RV32I without fence/CSR, plus the M extension). */
public enum Op {
    ADD(Fmt.R, 0x33, 0, 0x00), SUB(Fmt.R, 0x33, 0, 0x20), SLL(Fmt.R, 0x33, 1, 0x00),
    SLT(Fmt.R, 0x33, 2, 0x00), SLTU(Fmt.R, 0x33, 3, 0x00), XOR(Fmt.R, 0x33, 4, 0x00),
    SRL(Fmt.R, 0x33, 5, 0x00), SRA(Fmt.R, 0x33, 5, 0x20), OR(Fmt.R, 0x33, 6, 0x00), AND(Fmt.R, 0x33, 7, 0x00),
    MUL(Fmt.R, 0x33, 0, 0x01), MULH(Fmt.R, 0x33, 1, 0x01), MULHSU(Fmt.R, 0x33, 2, 0x01), MULHU(Fmt.R, 0x33, 3, 0x01),
    DIV(Fmt.R, 0x33, 4, 0x01), DIVU(Fmt.R, 0x33, 5, 0x01), REM(Fmt.R, 0x33, 6, 0x01), REMU(Fmt.R, 0x33, 7, 0x01),
    ADDI(Fmt.I, 0x13, 0, 0), SLTI(Fmt.I, 0x13, 2, 0), SLTIU(Fmt.I, 0x13, 3, 0), XORI(Fmt.I, 0x13, 4, 0),
    ORI(Fmt.I, 0x13, 6, 0), ANDI(Fmt.I, 0x13, 7, 0),
    SLLI(Fmt.SH, 0x13, 1, 0x00), SRLI(Fmt.SH, 0x13, 5, 0x00), SRAI(Fmt.SH, 0x13, 5, 0x20),
    LB(Fmt.I, 0x03, 0, 0), LH(Fmt.I, 0x03, 1, 0), LW(Fmt.I, 0x03, 2, 0), LBU(Fmt.I, 0x03, 4, 0), LHU(Fmt.I, 0x03, 5, 0),
    JALR(Fmt.I, 0x67, 0, 0),
    SB(Fmt.S, 0x23, 0, 0), SH(Fmt.S, 0x23, 1, 0), SW(Fmt.S, 0x23, 2, 0),
    BEQ(Fmt.B, 0x63, 0, 0), BNE(Fmt.B, 0x63, 1, 0), BLT(Fmt.B, 0x63, 4, 0), BGE(Fmt.B, 0x63, 5, 0),
    BLTU(Fmt.B, 0x63, 6, 0), BGEU(Fmt.B, 0x63, 7, 0),
    LUI(Fmt.U, 0x37, 0, 0), AUIPC(Fmt.U, 0x17, 0, 0), JAL(Fmt.J, 0x6F, 0, 0),
    ECALL(Fmt.SYS, 0x73, 0, 0), EBREAK(Fmt.SYS, 0x73, 0, 0);

    final Fmt format;
    final int opcode;
    final int funct3;
    final int funct7;

    Op(Fmt format, int opcode, int funct3, int funct7) {
        this.format = format;
        this.opcode = opcode;
        this.funct3 = funct3;
        this.funct7 = funct7;
    }

    /** Lower-case assembler mnemonic. */
    public String mnemonic() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for load instructions (I-format with opcode 0x03). */
    boolean isLoad() {
        return opcode == 0x03;
    }
}
