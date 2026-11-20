package ae.minilang;

import ae.minilang.Bytecode.Function;
import ae.minilang.LangException.Kind;
import java.util.List;

/** Stack virtual machine. Only runs bytecode that passed {@link Verifier}. */
final class Vm {
    private Vm() { }

    static void run(Bytecode b, List<Long> out, long stepLimit) {
        int[] maxStack = Verifier.verify(b);
        int widest = 0;
        for (int m : maxStack) {
            widest = Math.max(widest, m);
        }
        int maxDepth = Interp.MAX_DEPTH;
        long[] stack = new long[maxDepth * (widest + 1) + 8];
        int sp = 0;
        int[] fnOf = new int[maxDepth + 1];
        int[] pcOf = new int[maxDepth + 1];
        long[][] locals = new long[maxDepth + 1][];
        int depth = 1;
        fnOf[1] = 0;
        locals[1] = new long[b.functions().get(0).nLocals()];
        long steps = 0;
        while (true) {
            if (++steps > stepLimit) {
                throw new LangException(Kind.STEP_LIMIT, "step budget exhausted");
            }
            Function f = b.functions().get(fnOf[depth]);
            long[] code = f.code();
            int pc = pcOf[depth];
            Op op = Op.decode(code[pc]);
            int next = pc + 1 + op.operands;
            switch (op) {
                case PUSH -> stack[sp++] = code[pc + 1];
                case POP -> sp--;
                case LOAD -> stack[sp++] = locals[depth][(int) code[pc + 1]];
                case STORE -> locals[depth][(int) code[pc + 1]] = stack[--sp];
                case NEG -> stack[sp - 1] = Arith.neg(stack[sp - 1]);
                case NOT -> stack[sp - 1] = Arith.not(stack[sp - 1]);
                case JMP -> next = (int) code[pc + 1];
                case JZ -> {
                    if (stack[--sp] == 0) {
                        next = (int) code[pc + 1];
                    }
                }
                case PRINT -> out.add(stack[--sp]);
                case CALL -> {
                    if (depth + 1 > maxDepth) {
                        throw new LangException(Kind.STACK_OVERFLOW, "call depth exceeds " + maxDepth);
                    }
                    Function callee = b.functions().get((int) code[pc + 1]);
                    long[] frame = new long[callee.nLocals()];
                    for (int i = callee.arity() - 1; i >= 0; i--) {
                        frame[i] = stack[--sp];
                    }
                    pcOf[depth] = next;
                    depth++;
                    fnOf[depth] = (int) code[pc + 1];
                    pcOf[depth] = 0;
                    locals[depth] = frame;
                    continue;
                }
                case RET -> {
                    long v = stack[--sp];
                    if (depth == 1) {
                        return;
                    }
                    locals[depth] = null;
                    depth--;
                    stack[sp++] = v;
                    continue;
                }
                default -> {
                    long r = stack[--sp];
                    long l = stack[sp - 1];
                    stack[sp - 1] = Arith.binary(op.binOp(), l, r);
                }
            }
            pcOf[depth] = next;
        }
    }
}
