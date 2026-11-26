package ae.classfile;

/**
 * One decoded bytecode instruction.
 *
 * <p>Operand meaning by opcode: local-variable index (loads, stores, ret, and the implicit
 * {@code _0.._3} forms), constant-pool index, {@code bipush}/{@code sipush} value, {@code newarray}
 * type code, or the low bound of a {@code tableswitch}. {@code operand2} is the {@code iinc}
 * constant, the {@code invokeinterface}/{@code multianewarray} count, or the high bound of a
 * {@code tableswitch}. {@code targets} holds absolute branch targets; for switches element 0 is
 * the default and the rest follow {@code keys} (or low..high).
 *
 * @param wide true for the {@code wide}-prefixed forms (mnemonic gets a {@code _w} suffix)
 */
public record Instruction(int offset, int opcode, boolean wide, int length, int operand, int operand2,
                          int[] targets, int[] keys) {
    private static final int[] NONE = {};

    static Instruction simple(int offset, int opcode, int length, int operand, int operand2) {
        return new Instruction(offset, opcode, false, length, operand, operand2, NONE, NONE);
    }

    public String mnemonic() {
        return Opcodes.name(opcode) + (wide ? "_w" : "");
    }
}
