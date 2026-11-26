package ae.classfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict bytecode decoder plus a javap-style operand renderer. */
public final class Disassembler {
    private static final String[] ATYPES = {"boolean", "char", "float", "double", "byte", "short", "int", "long"};

    private Disassembler() {
    }

    /** Decodes a whole code array; operand bounds, wide targets, switch layout and atype are validated. */
    public static List<Instruction> decode(byte[] code) {
        List<Instruction> out = new ArrayList<>();
        int pos = 0;
        while (pos < code.length) {
            Instruction in = decodeOne(code, pos);
            out.add(in);
            pos += in.length();
        }
        return out;
    }

    private static Instruction decodeOne(byte[] c, int pos) {
        int op = c[pos] & 0xFF;
        String name = Opcodes.name(op);
        int fixed = Opcodes.fixedLength(op);
        if (fixed > 0 && pos + fixed > c.length) {
            throw new ClassFormatException("truncated " + name + " at " + pos);
        }
        switch (Opcodes.kind(op)) {
            case NONE:
                // implicit local forms carry the slot number in the opcode itself
                if (op >= 0x1a && op <= 0x2d) {
                    return Instruction.simple(pos, op, 1, (op - 0x1a) % 4, 0);
                }
                if (op >= 0x3b && op <= 0x4e) {
                    return Instruction.simple(pos, op, 1, (op - 0x3b) % 4, 0);
                }
                return Instruction.simple(pos, op, 1, 0, 0);
            case VALUE:
                return Instruction.simple(pos, op, fixed, fixed == 2 ? c[pos + 1] : s2(c, pos + 1), 0);
            case CP:
                return Instruction.simple(pos, op, fixed, fixed == 2 ? u1(c, pos + 1) : u2(c, pos + 1), 0);
            case LOCAL:
                return Instruction.simple(pos, op, 2, u1(c, pos + 1), 0);
            case IINC:
                return Instruction.simple(pos, op, 3, u1(c, pos + 1), c[pos + 2]);
            case BRANCH: {
                int off = fixed == 3 ? s2(c, pos + 1) : s4(c, pos + 1);
                return new Instruction(pos, op, false, fixed, 0, 0, new int[] {pos + off}, new int[0]);
            }
            case NEWARRAY: {
                int t = u1(c, pos + 1);
                if (t < 4 || t > 11) {
                    throw new ClassFormatException("bad newarray type " + t + " at " + pos);
                }
                return Instruction.simple(pos, op, 2, t, 0);
            }
            case CP_COUNT: {
                int idx = u2(c, pos + 1);
                int n = u1(c, pos + 3);
                if (op == Opcodes.INVOKEINTERFACE) {
                    if (n == 0) {
                        throw new ClassFormatException("invokeinterface count must be positive at " + pos);
                    }
                    if (u1(c, pos + 4) != 0) {
                        throw new ClassFormatException("invokeinterface operand byte must be zero at " + pos);
                    }
                    return Instruction.simple(pos, op, 5, idx, n);
                }
                if (n == 0) {
                    throw new ClassFormatException("multianewarray dimensions must be positive at " + pos);
                }
                return Instruction.simple(pos, op, 4, idx, n);
            }
            case INDY:
                if (u2(c, pos + 3) != 0) {
                    throw new ClassFormatException("invokedynamic operand bytes must be zero at " + pos);
                }
                return Instruction.simple(pos, op, 5, u2(c, pos + 1), 0);
            case WIDE:
                return decodeWide(c, pos);
            case SWITCH:
                return decodeSwitch(c, pos, op);
            default:
                throw new ClassFormatException("unhandled opcode " + name);
        }
    }

    private static Instruction decodeWide(byte[] c, int pos) {
        if (pos + 1 >= c.length) {
            throw new ClassFormatException("truncated wide at " + pos);
        }
        int w = c[pos + 1] & 0xFF;
        boolean iinc = w == Opcodes.IINC;
        boolean local = (w >= 0x15 && w <= 0x19) || (w >= 0x36 && w <= 0x3a) || w == Opcodes.RET;
        if (!iinc && !local) {
            throw new ClassFormatException("wide cannot modify opcode 0x" + Integer.toHexString(w) + " at " + pos);
        }
        int length = iinc ? 6 : 4;
        if (pos + length > c.length) {
            throw new ClassFormatException("truncated wide at " + pos);
        }
        return new Instruction(pos, w, true, length, u2(c, pos + 2), iinc ? s2(c, pos + 4) : 0,
            new int[0], new int[0]);
    }

    private static Instruction decodeSwitch(byte[] c, int pos, int op) {
        int p = (pos + 4) & ~3;           // operands start at the next multiple of 4
        boolean table = op == Opcodes.TABLESWITCH;
        long header = table ? 12 : 8;
        if (p + header > c.length) {
            throw new ClassFormatException("truncated " + Opcodes.name(op) + " at " + pos);
        }
        int dflt = pos + s4(c, p);
        if (table) {
            int low = s4(c, p + 4);
            int high = s4(c, p + 8);
            if (low > high) {
                throw new ClassFormatException("tableswitch low > high at " + pos);
            }
            long count = (long) high - low + 1;
            long end = p + 12 + 4 * count;
            if (end > c.length) {
                throw new ClassFormatException("truncated tableswitch at " + pos);
            }
            int[] targets = new int[(int) count + 1];
            targets[0] = dflt;
            for (int i = 0; i < count; i++) {
                targets[i + 1] = pos + s4(c, p + 12 + 4 * i);
            }
            return new Instruction(pos, op, false, (int) end - pos, low, high, targets, new int[0]);
        }
        int npairs = s4(c, p + 4);
        if (npairs < 0) {
            throw new ClassFormatException("negative lookupswitch pair count at " + pos);
        }
        long end = p + 8 + 8L * npairs;
        if (end > c.length) {
            throw new ClassFormatException("truncated lookupswitch at " + pos);
        }
        int[] keys = new int[npairs];
        int[] targets = new int[npairs + 1];
        targets[0] = dflt;
        for (int i = 0; i < npairs; i++) {
            keys[i] = s4(c, p + 8 + 8 * i);
            if (i > 0 && keys[i] <= keys[i - 1]) {
                throw new ClassFormatException("lookupswitch keys not strictly ascending at " + pos);
            }
            targets[i + 1] = pos + s4(c, p + 12 + 8 * i);
        }
        return new Instruction(pos, op, false, (int) end - pos, 0, 0, targets, keys);
    }

    /** Every branch and switch target must be the start of a decoded instruction. */
    public static void validateTargets(List<Instruction> ins, int codeLength) {
        Set<Integer> starts = new HashSet<>();
        for (Instruction in : ins) {
            starts.add(in.offset());
        }
        for (Instruction in : ins) {
            for (int t : in.targets()) {
                if (!starts.contains(t)) {
                    throw new ClassFormatException("branch target " + t + " of " + in.mnemonic() + " at "
                        + in.offset() + " is not an instruction start (code length " + codeLength + ")");
                }
            }
        }
    }

    /** javap-style text for one instruction, without its offset. */
    public static String render(Instruction in) {
        String name = in.mnemonic();
        return switch (Opcodes.kind(in.opcode())) {
            case NONE -> name;
            case VALUE, LOCAL -> name + " " + in.operand();
            case CP -> name + " #" + in.operand();
            case IINC -> name + " " + in.operand() + ", " + in.operand2();
            case BRANCH -> name + " " + in.targets()[0];
            case NEWARRAY -> name + " " + ATYPES[in.operand() - 4];
            case CP_COUNT -> name + " #" + in.operand() + ", " + in.operand2();
            case INDY -> name + " #" + in.operand() + ", 0";
            case WIDE -> throw new IllegalStateException("wide is folded into its operand instruction");
            case SWITCH -> renderSwitch(in, name);
        };
    }

    private static String renderSwitch(Instruction in, String name) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < in.targets().length; i++) {
            long key = in.opcode() == Opcodes.TABLESWITCH ? (long) in.operand() + (i - 1) : in.keys()[i - 1];
            parts.add(key + ": " + in.targets()[i]);
        }
        parts.add("default: " + in.targets()[0]);
        return name + " {" + String.join(", ", parts) + "}";
    }

    private static int u1(byte[] c, int i) {
        return c[i] & 0xFF;
    }

    private static int u2(byte[] c, int i) {
        return ((c[i] & 0xFF) << 8) | (c[i + 1] & 0xFF);
    }

    private static int s2(byte[] c, int i) {
        return (short) u2(c, i);
    }

    private static int s4(byte[] c, int i) {
        return (u2(c, i) << 16) | u2(c, i + 2);
    }
}
