package ae.rv;

/**
 * RV32IM interpreter with byte-addressable little-endian memory. Loads, stores and instruction fetches must be
 * naturally aligned (else {@link Trap}); accesses outside memory trap. {@code x0} is hard-wired to zero.
 */
public final class Cpu {
    private final byte[] mem;
    private final int[] regs = new int[32];
    private int pc;
    private final StringBuilder out = new StringBuilder();
    private int exitCode;

    public Cpu(int memBytes) {
        if (memBytes <= 0 || memBytes % 4 != 0) {
            throw new IllegalArgumentException("memory size must be a positive multiple of 4");
        }
        mem = new byte[memBytes];
        regs[2] = memBytes;
    }

    /** Copies the words to address 0 (little-endian) and sets pc to 0. */
    public void load(int[] words) {
        if ((long) words.length * 4 > mem.length) {
            throw new IllegalArgumentException("program does not fit in memory");
        }
        for (int i = 0; i < words.length; i++) {
            store(i * 4, words[i], 4);
        }
        pc = 0;
    }

    public int reg(int i) {
        return regs[i];
    }

    public int pc() {
        return pc;
    }

    public byte memByte(int addr) {
        return mem[addr];
    }

    public int memWord(int addr) {
        return load(addr, 4, false);
    }

    private void setReg(int i, int v) {
        if (i != 0) {
            regs[i] = v;
        }
    }

    private void check(long addr, int size, String what) {
        if (addr < 0 || addr + size > mem.length) {
            throw new Trap(what + " out of range at 0x" + Long.toHexString(addr & 0xFFFFFFFFL));
        }
        if (addr % size != 0) {
            throw new Trap(what + " misaligned at 0x" + Long.toHexString(addr));
        }
    }

    private int load(int a, int size, boolean unsigned) {
        long addr = a & 0xFFFFFFFFL;
        check(addr, size, "load");
        int v = 0;
        for (int i = size - 1; i >= 0; i--) {
            v = (v << 8) | (mem[(int) addr + i] & 0xFF);
        }
        if (!unsigned && size < 4) {
            int shift = 32 - 8 * size;
            v = (v << shift) >> shift;
        }
        return v;
    }

    private void store(int a, int value, int size) {
        long addr = a & 0xFFFFFFFFL;
        check(addr, size, "store");
        for (int i = 0; i < size; i++) {
            mem[(int) addr + i] = (byte) (value >>> (8 * i));
        }
    }

    /** Executes one instruction; returns the halt reason if it halted, else null. */
    public Halt step() {
        long fetch = pc & 0xFFFFFFFFL;
        check(fetch, 4, "fetch");
        int word = 0;
        for (int i = 3; i >= 0; i--) {
            word = (word << 8) | (mem[pc + i] & 0xFF);
        }
        Instr in;
        try {
            in = Codec.decode(word);
        } catch (IllegalInstructionException e) {
            throw new Trap("illegal instruction 0x" + Integer.toHexString(word) + " at 0x" + Integer.toHexString(pc));
        }
        int next = pc + 4;
        int a = regs[in.rs1()];
        int b = regs[in.rs2()];
        int imm = in.imm();
        Halt halt = null;
        switch (in.op()) {
            case ADD -> setReg(in.rd(), a + b);
            case SUB -> setReg(in.rd(), a - b);
            case SLL -> setReg(in.rd(), a << (b & 31));
            case SLT -> setReg(in.rd(), a < b ? 1 : 0);
            case SLTU -> setReg(in.rd(), Integer.compareUnsigned(a, b) < 0 ? 1 : 0);
            case XOR -> setReg(in.rd(), a ^ b);
            case SRL -> setReg(in.rd(), a >>> (b & 31));
            case SRA -> setReg(in.rd(), a >> (b & 31));
            case OR -> setReg(in.rd(), a | b);
            case AND -> setReg(in.rd(), a & b);
            case MUL -> setReg(in.rd(), a * b);
            case MULH -> setReg(in.rd(), (int) (((long) a * (long) b) >> 32));
            case MULHSU -> setReg(in.rd(), (int) (((long) a * (b & 0xFFFFFFFFL)) >> 32));
            case MULHU -> setReg(in.rd(), (int) (((a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL)) >>> 32));
            case DIV -> setReg(in.rd(), b == 0 ? -1 : (a == Integer.MIN_VALUE && b == -1) ? a : a / b);
            case DIVU -> setReg(in.rd(), b == 0 ? -1 : Integer.divideUnsigned(a, b));
            case REM -> setReg(in.rd(), b == 0 ? a : (a == Integer.MIN_VALUE && b == -1) ? 0 : a % b);
            case REMU -> setReg(in.rd(), b == 0 ? a : Integer.remainderUnsigned(a, b));
            case ADDI -> setReg(in.rd(), a + imm);
            case SLTI -> setReg(in.rd(), a < imm ? 1 : 0);
            case SLTIU -> setReg(in.rd(), Integer.compareUnsigned(a, imm) < 0 ? 1 : 0);
            case XORI -> setReg(in.rd(), a ^ imm);
            case ORI -> setReg(in.rd(), a | imm);
            case ANDI -> setReg(in.rd(), a & imm);
            case SLLI -> setReg(in.rd(), a << imm);
            case SRLI -> setReg(in.rd(), a >>> imm);
            case SRAI -> setReg(in.rd(), a >> imm);
            case LB -> setReg(in.rd(), load(a + imm, 1, false));
            case LBU -> setReg(in.rd(), load(a + imm, 1, true));
            case LH -> setReg(in.rd(), load(a + imm, 2, false));
            case LHU -> setReg(in.rd(), load(a + imm, 2, true));
            case LW -> setReg(in.rd(), load(a + imm, 4, false));
            case SB -> store(a + imm, b, 1);
            case SH -> store(a + imm, b, 2);
            case SW -> store(a + imm, b, 4);
            case BEQ -> { if (a == b) { next = pc + imm; } }
            case BNE -> { if (a != b) { next = pc + imm; } }
            case BLT -> { if (a < b) { next = pc + imm; } }
            case BGE -> { if (a >= b) { next = pc + imm; } }
            case BLTU -> { if (Integer.compareUnsigned(a, b) < 0) { next = pc + imm; } }
            case BGEU -> { if (Integer.compareUnsigned(a, b) >= 0) { next = pc + imm; } }
            case LUI -> setReg(in.rd(), imm << 12);
            case AUIPC -> setReg(in.rd(), pc + (imm << 12));
            case JAL -> { setReg(in.rd(), pc + 4); next = pc + imm; }
            case JALR -> { setReg(in.rd(), pc + 4); next = (a + imm) & ~1; }
            case EBREAK -> halt = Halt.BREAK;
            case ECALL -> halt = ecall();
        }
        pc = next;
        return halt;
    }

    private Halt ecall() {
        int number = regs[17];
        int a0 = regs[10];
        switch (number) {
            case 93 -> {
                exitCode = a0;
                return Halt.EXIT;
            }
            case 1 -> out.append(a0).append('\n');
            case 11 -> out.append((char) (a0 & 0xFF));
            default -> throw new Trap("unknown ecall " + number);
        }
        return null;
    }

    /** Runs until exit, ebreak or {@code maxSteps} executed instructions (the halting one counts). */
    public RunResult run(long maxSteps) {
        long steps = 0;
        while (steps < maxSteps) {
            Halt h = step();
            steps++;
            if (h != null) {
                return new RunResult(h, h == Halt.EXIT ? exitCode : 0, steps, out.toString());
            }
        }
        return new RunResult(Halt.STEP_LIMIT, 0, steps, out.toString());
    }
}
