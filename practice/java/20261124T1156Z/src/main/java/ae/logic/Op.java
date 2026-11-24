package ae.logic;

/** Gate operations. Operand rules live here so parser, evaluators and docs agree. */
public enum Op {
    AND(2, -1), OR(2, -1), XOR(2, -1), NAND(2, -1), NOR(2, -1), XNOR(2, -1),
    NOT(1, 1), BUF(1, 1), MUX(3, 3), DFF(1, 1), CONST0(0, 0), CONST1(0, 0);

    private final int min;
    private final int max; // -1 = unbounded

    Op(int min, int max) { this.min = min; this.max = max; }

    /** Returns an error message if {@code n} operands are not allowed, else null. */
    String arityError(int n) {
        if (max == -1) return n >= min ? null : name() + " needs at least " + min + " operands";
        if (min == max) {
            if (n == min) return null;
            return min == 0 ? name() + " takes no operands" : name() + " takes exactly " + min + " operand" + (min == 1 ? "" : "s");
        }
        return null;
    }

    /** True for gates whose output is not computed from operands during combinational evaluation. */
    boolean isSource() { return this == DFF; }

    boolean apply(boolean[] v, int[] in) {
        switch (this) {
            case CONST0: return false;
            case CONST1: return true;
            case NOT: return !v[in[0]];
            case BUF: case DFF: return v[in[0]];
            case MUX: return v[in[0]] ? v[in[2]] : v[in[1]];
            default: break;
        }
        boolean acc = v[in[0]];
        for (int i = 1; i < in.length; i++) {
            boolean x = v[in[i]];
            switch (this) {
                case AND: case NAND: acc &= x; break;
                case OR: case NOR: acc |= x; break;
                default: acc ^= x; break;
            }
        }
        return this == NAND || this == NOR || this == XNOR ? !acc : acc;
    }

    long applyPacked(long[] v, int[] in) {
        switch (this) {
            case CONST0: return 0L;
            case CONST1: return -1L;
            case NOT: return ~v[in[0]];
            case BUF: case DFF: return v[in[0]];
            case MUX: return (v[in[0]] & v[in[2]]) | (~v[in[0]] & v[in[1]]);
            default: break;
        }
        long acc = v[in[0]];
        for (int i = 1; i < in.length; i++) {
            long x = v[in[i]];
            switch (this) {
                case AND: case NAND: acc &= x; break;
                case OR: case NOR: acc |= x; break;
                default: acc ^= x; break;
            }
        }
        return this == NAND || this == NOR || this == XNOR ? ~acc : acc;
    }
}
