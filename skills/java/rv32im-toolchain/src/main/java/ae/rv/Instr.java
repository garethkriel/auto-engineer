package ae.rv;

import java.util.Objects;

/**
 * One decoded instruction in canonical form: fields the format does not use must be 0.
 * Immediates: I/S in -2048..2047, SH shamt 0..31, B even in -4096..4094, J even in -2^20..2^20-2,
 * U the 20-bit upper value 0..0xFFFFF.
 */
public record Instr(Op op, int rd, int rs1, int rs2, int imm) {
    public Instr {
        Objects.requireNonNull(op, "op");
        reg(rd, "rd");
        reg(rs1, "rs1");
        reg(rs2, "rs2");
        switch (op.format) {
            case R -> { require(imm == 0, "imm must be 0"); }
            case I -> { require(rs2 == 0, "rs2 must be 0"); range(imm, -2048, 2047); }
            case SH -> { require(rs2 == 0, "rs2 must be 0"); range(imm, 0, 31); }
            case S -> { require(rd == 0, "rd must be 0"); range(imm, -2048, 2047); }
            case B -> {
                require(rd == 0, "rd must be 0");
                require((imm & 1) == 0, "branch offset must be even");
                range(imm, -4096, 4094);
            }
            case U -> { require(rs1 == 0 && rs2 == 0, "rs1/rs2 must be 0"); range(imm, 0, 0xFFFFF); }
            case J -> {
                require(rs1 == 0 && rs2 == 0, "rs1/rs2 must be 0");
                require((imm & 1) == 0, "jump offset must be even");
                range(imm, -(1 << 20), (1 << 20) - 2);
            }
            case SYS -> { require(rd == 0 && rs1 == 0 && rs2 == 0 && imm == 0, "system instruction takes no operands"); }
        }
    }

    private static void reg(int r, String name) {
        require(r >= 0 && r < 32, name + " out of range: " + r);
    }

    private static void range(int v, int lo, int hi) {
        require(v >= lo && v <= hi, "immediate out of range: " + v);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Assembler syntax with numeric branch/jump offsets; assembling it again yields the same word. */
    @Override
    public String toString() {
        String m = op.mnemonic();
        return switch (op.format) {
            case R -> m + " x" + rd + ", x" + rs1 + ", x" + rs2;
            case I -> op.isLoad() || op == Op.JALR
                    ? m + " x" + rd + ", " + imm + "(x" + rs1 + ")"
                    : m + " x" + rd + ", x" + rs1 + ", " + imm;
            case SH -> m + " x" + rd + ", x" + rs1 + ", " + imm;
            case S -> m + " x" + rs2 + ", " + imm + "(x" + rs1 + ")";
            case B -> m + " x" + rs1 + ", x" + rs2 + ", " + imm;
            case U -> m + " x" + rd + ", 0x" + Integer.toHexString(imm);
            case J -> m + " x" + rd + ", " + imm;
            case SYS -> m;
        };
    }
}
