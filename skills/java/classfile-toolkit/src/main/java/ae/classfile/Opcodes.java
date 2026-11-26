package ae.classfile;

/** The JVM opcode table: mnemonics, operand shapes and fixed stack effects. */
public final class Opcodes {
    /** Number of defined opcodes (0x00..0xc9). */
    public static final int COUNT = 202;

    public static final int LDC = 0x12, LDC_W = 0x13, LDC2_W = 0x14, IINC = 0x84, TABLESWITCH = 0xaa,
        LOOKUPSWITCH = 0xab, WIDE = 0xc4, GETSTATIC = 0xb2, PUTSTATIC = 0xb3, GETFIELD = 0xb4, PUTFIELD = 0xb5,
        INVOKEVIRTUAL = 0xb6, INVOKESPECIAL = 0xb7, INVOKESTATIC = 0xb8, INVOKEINTERFACE = 0xb9,
        INVOKEDYNAMIC = 0xba, NEW = 0xbb, NEWARRAY = 0xbc, ANEWARRAY = 0xbd, CHECKCAST = 0xc0, INSTANCEOF = 0xc1,
        MULTIANEWARRAY = 0xc5, GOTO = 0xa7, JSR = 0xa8, RET = 0xa9, GOTO_W = 0xc8, JSR_W = 0xc9,
        ATHROW = 0xbf;

    /** How an instruction's operands are printed. */
    enum Kind { NONE, VALUE, CP, LOCAL, IINC, BRANCH, NEWARRAY, CP_COUNT, INDY, SWITCH, WIDE }

    private static final String[] NAMES = (
        "nop aconst_null iconst_m1 iconst_0 iconst_1 iconst_2 iconst_3 iconst_4 iconst_5 lconst_0 " +
        "lconst_1 fconst_0 fconst_1 fconst_2 dconst_0 dconst_1 bipush sipush ldc ldc_w ldc2_w iload " +
        "lload fload dload aload iload_0 iload_1 iload_2 iload_3 lload_0 lload_1 lload_2 lload_3 " +
        "fload_0 fload_1 fload_2 fload_3 dload_0 dload_1 dload_2 dload_3 aload_0 aload_1 aload_2 " +
        "aload_3 iaload laload faload daload aaload baload caload saload istore lstore fstore " +
        "dstore astore istore_0 istore_1 istore_2 istore_3 lstore_0 lstore_1 lstore_2 lstore_3 " +
        "fstore_0 fstore_1 fstore_2 fstore_3 dstore_0 dstore_1 dstore_2 dstore_3 astore_0 astore_1 " +
        "astore_2 astore_3 iastore lastore fastore dastore aastore bastore castore sastore pop pop2 " +
        "dup dup_x1 dup_x2 dup2 dup2_x1 dup2_x2 swap iadd ladd fadd dadd isub lsub fsub dsub imul " +
        "lmul fmul dmul idiv ldiv fdiv ddiv irem lrem frem drem ineg lneg fneg dneg ishl lshl ishr " +
        "lshr iushr lushr iand land ior lor ixor lxor iinc i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i " +
        "d2l d2f i2b i2c i2s lcmp fcmpl fcmpg dcmpl dcmpg ifeq ifne iflt ifge ifgt ifle if_icmpeq " +
        "if_icmpne if_icmplt if_icmpge if_icmpgt if_icmple if_acmpeq if_acmpne goto jsr ret " +
        "tableswitch lookupswitch ireturn lreturn freturn dreturn areturn return getstatic " +
        "putstatic getfield putfield invokevirtual invokespecial invokestatic invokeinterface " +
        "invokedynamic new newarray anewarray arraylength athrow checkcast instanceof monitorenter " +
        "monitorexit wide multianewarray ifnull ifnonnull goto_w jsr_w"
        ).split(" ");

    private static final int[] FIXED_LENGTH = new int[COUNT];
    private static final Kind[] KINDS = new Kind[COUNT];
    /** {pops, pushes} in slots, or null where the effect depends on the constant pool. */
    private static final int[][] EFFECT = new int[COUNT][];

    static {
        if (NAMES.length != COUNT) {
            throw new IllegalStateException("opcode table has " + NAMES.length + " names");
        }
        java.util.Arrays.fill(KINDS, Kind.NONE);
        java.util.Arrays.fill(FIXED_LENGTH, 1);
        range(0x00, 0x00, 0, 0);
        range(0x01, 0x08, 0, 1);       // aconst_null, iconst_*
        range(0x09, 0x0a, 0, 2);       // lconst
        range(0x0b, 0x0d, 0, 1);       // fconst
        range(0x0e, 0x0f, 0, 2);       // dconst
        range(0x10, 0x11, 0, 1);       // bipush, sipush
        // loads: i f a push 1 slot, l d push 2; explicit-index forms first, then _0.._3 forms
        range(0x15, 0x15, 0, 1); range(0x16, 0x16, 0, 2); range(0x17, 0x17, 0, 1); range(0x18, 0x18, 0, 2);
        range(0x19, 0x19, 0, 1);
        range(0x1a, 0x1d, 0, 1); range(0x1e, 0x21, 0, 2); range(0x22, 0x25, 0, 1); range(0x26, 0x29, 0, 2);
        range(0x2a, 0x2d, 0, 1);
        range(0x2e, 0x2e, 2, 1); range(0x2f, 0x2f, 2, 2); range(0x30, 0x30, 2, 1); range(0x31, 0x31, 2, 2);
        range(0x32, 0x35, 2, 1);       // aaload baload caload saload
        range(0x36, 0x36, 1, 0); range(0x37, 0x37, 2, 0); range(0x38, 0x38, 1, 0); range(0x39, 0x39, 2, 0);
        range(0x3a, 0x3a, 1, 0);
        range(0x3b, 0x3e, 1, 0); range(0x3f, 0x42, 2, 0); range(0x43, 0x46, 1, 0); range(0x47, 0x4a, 2, 0);
        range(0x4b, 0x4e, 1, 0);
        range(0x4f, 0x4f, 3, 0); range(0x50, 0x50, 4, 0); range(0x51, 0x51, 3, 0); range(0x52, 0x52, 4, 0);
        range(0x53, 0x56, 3, 0);       // aastore bastore castore sastore
        range(0x57, 0x57, 1, 0);       // pop
        range(0x58, 0x58, 2, 0);       // pop2
        range(0x59, 0x59, 1, 2);       // dup
        range(0x5a, 0x5a, 2, 3);       // dup_x1
        range(0x5b, 0x5b, 3, 4);       // dup_x2
        range(0x5c, 0x5c, 2, 4);       // dup2
        range(0x5d, 0x5d, 3, 5);       // dup2_x1
        range(0x5e, 0x5e, 4, 6);       // dup2_x2
        range(0x5f, 0x5f, 2, 2);       // swap
        for (int base = 0x60; base < 0x74; base += 4) {   // add sub mul div rem: i l f d
            range(base, base, 2, 1); range(base + 1, base + 1, 4, 2);
            range(base + 2, base + 2, 2, 1); range(base + 3, base + 3, 4, 2);
        }
        range(0x74, 0x74, 1, 1); range(0x75, 0x75, 2, 2); range(0x76, 0x76, 1, 1); range(0x77, 0x77, 2, 2); // neg
        range(0x78, 0x78, 2, 1); range(0x79, 0x79, 3, 2); range(0x7a, 0x7a, 2, 1); range(0x7b, 0x7b, 3, 2);
        range(0x7c, 0x7c, 2, 1); range(0x7d, 0x7d, 3, 2);   // shl shr ushr
        range(0x7e, 0x7e, 2, 1); range(0x7f, 0x7f, 4, 2); range(0x80, 0x80, 2, 1); range(0x81, 0x81, 4, 2);
        range(0x82, 0x82, 2, 1); range(0x83, 0x83, 4, 2);   // and or xor
        range(0x84, 0x84, 0, 0);       // iinc
        range(0x85, 0x85, 1, 2); range(0x86, 0x86, 1, 1); range(0x87, 0x87, 1, 2);   // i2l i2f i2d
        range(0x88, 0x88, 2, 1); range(0x89, 0x89, 2, 1); range(0x8a, 0x8a, 2, 2);   // l2i l2f l2d
        range(0x8b, 0x8b, 1, 1); range(0x8c, 0x8c, 1, 2); range(0x8d, 0x8d, 1, 2);   // f2i f2l f2d
        range(0x8e, 0x8e, 2, 1); range(0x8f, 0x8f, 2, 2); range(0x90, 0x90, 2, 1);   // d2i d2l d2f
        range(0x91, 0x93, 1, 1);       // i2b i2c i2s
        range(0x94, 0x94, 4, 1); range(0x95, 0x96, 2, 1); range(0x97, 0x98, 4, 1);   // lcmp fcmp dcmp
        range(0x99, 0x9e, 1, 0);       // ifeq .. ifle
        range(0x9f, 0xa6, 2, 0);       // if_icmp*, if_acmp*
        range(0xa7, 0xa7, 0, 0);       // goto
        range(0xaa, 0xab, 1, 0);       // switches
        range(0xac, 0xac, 1, 0); range(0xad, 0xad, 2, 0); range(0xae, 0xae, 1, 0); range(0xaf, 0xaf, 2, 0);
        range(0xb0, 0xb0, 1, 0); range(0xb1, 0xb1, 0, 0);   // returns
        range(0xbb, 0xbb, 0, 1);       // new
        range(0xbc, 0xbd, 1, 1);       // newarray anewarray
        range(0xbe, 0xbe, 1, 1);       // arraylength
        range(0xbf, 0xbf, 1, 0);       // athrow
        range(0xc0, 0xc1, 1, 1);       // checkcast instanceof
        range(0xc2, 0xc3, 1, 0);       // monitorenter monitorexit
        range(0xc6, 0xc7, 1, 0);       // ifnull ifnonnull
        range(0xc8, 0xc8, 0, 0);       // goto_w
        // operand byte counts and print shapes
        shape(0x10, 2, Kind.VALUE); shape(0x11, 3, Kind.VALUE);
        shape(0x12, 2, Kind.CP); shape(0x13, 3, Kind.CP); shape(0x14, 3, Kind.CP);
        for (int op = 0x15; op <= 0x19; op++) {
            shape(op, 2, Kind.LOCAL);
        }
        for (int op = 0x36; op <= 0x3a; op++) {
            shape(op, 2, Kind.LOCAL);
        }
        shape(0x84, 3, Kind.IINC);
        for (int op = 0x99; op <= 0xa8; op++) {
            shape(op, 3, Kind.BRANCH);
        }
        shape(0xa9, 2, Kind.LOCAL);
        shape(0xaa, -1, Kind.SWITCH); shape(0xab, -1, Kind.SWITCH);
        for (int op = 0xb2; op <= 0xb8; op++) {
            shape(op, 3, Kind.CP);
        }
        shape(0xb9, 5, Kind.CP_COUNT); shape(0xba, 5, Kind.INDY);
        shape(0xbb, 3, Kind.CP); shape(0xbc, 2, Kind.NEWARRAY); shape(0xbd, 3, Kind.CP);
        shape(0xc0, 3, Kind.CP); shape(0xc1, 3, Kind.CP);
        shape(0xc4, -1, Kind.WIDE);
        shape(0xc5, 4, Kind.CP_COUNT);
        shape(0xc6, 3, Kind.BRANCH); shape(0xc7, 3, Kind.BRANCH);
        shape(0xc8, 5, Kind.BRANCH); shape(0xc9, 5, Kind.BRANCH);
    }

    private Opcodes() {
    }

    private static void range(int from, int to, int pops, int pushes) {
        for (int op = from; op <= to; op++) {
            EFFECT[op] = new int[] {pops, pushes};
        }
    }

    private static void shape(int op, int length, Kind kind) {
        FIXED_LENGTH[op] = length;
        KINDS[op] = kind;
    }

    /** Mnemonic of a defined opcode; throws for undefined or reserved values. */
    public static String name(int op) {
        if (op < 0 || op >= COUNT) {
            throw new ClassFormatException("undefined opcode 0x" + Integer.toHexString(op & 0xFF));
        }
        return NAMES[op];
    }

    /** Total instruction length for fixed-length opcodes; -1 for switches and wide. */
    static int fixedLength(int op) {
        return FIXED_LENGTH[op];
    }

    static Kind kind(int op) {
        return KINDS[op];
    }

    /** {pops, pushes} in slots, or null when the effect depends on the constant pool (or is unsupported). */
    static int[] effect(int op) {
        return EFFECT[op];
    }

    /** Local-variable slots touched by a load/store/ret/iinc opcode (2 for long and double). */
    static int localSize(int op) {
        return switch (op) {
            case 0x16, 0x18, 0x37, 0x39 -> 2;
            default -> (op >= 0x1e && op <= 0x21) || (op >= 0x26 && op <= 0x29)
                || (op >= 0x3f && op <= 0x42) || (op >= 0x47 && op <= 0x4a) ? 2 : 1;
        };
    }
}
