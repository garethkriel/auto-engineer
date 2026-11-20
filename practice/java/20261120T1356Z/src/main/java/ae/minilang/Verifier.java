package ae.minilang;

import ae.minilang.Bytecode.Function;
import ae.minilang.LangException.Kind;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Abstract interpretation of stack depth; rejects anything the VM could not run safely. */
final class Verifier {
    private Verifier() { }

    /** Returns the maximum operand-stack depth of each function, or throws a VERIFY error. */
    static int[] verify(Bytecode b) {
        int n = b.functions().size();
        if (n == 0 || b.functions().get(0).arity() != 0) {
            throw fail("main", "function 0 (main) must exist and take no arguments");
        }
        int[] max = new int[n];
        for (int i = 0; i < n; i++) {
            max[i] = verifyFunction(b, i);
        }
        return max;
    }

    private static LangException fail(String fn, String msg) {
        return new LangException(Kind.VERIFY, fn + ": " + msg);
    }

    private static int verifyFunction(Bytecode b, int index) {
        Function f = b.functions().get(index);
        long[] code = f.code();
        String fn = f.name();
        if (f.nLocals() < f.arity()) {
            throw fail(fn, "fewer slots than parameters");
        }
        boolean[] boundary = new boolean[code.length + 1];
        for (int pc = 0; pc < code.length; ) {
            boundary[pc] = true;
            Op op = Op.decode(code[pc]);
            if (op == null) {
                throw fail(fn, "unknown opcode " + code[pc] + " at " + pc);
            }
            if (op.operands > 0 && pc + op.operands >= code.length) {
                throw fail(fn, "truncated operand of " + op + " at " + pc);
            }
            pc += 1 + op.operands;
        }
        int[] depth = new int[code.length];
        Arrays.fill(depth, -1);
        Deque<Integer> work = new ArrayDeque<>();
        if (code.length == 0) {
            throw fail(fn, "code falls off the end");
        }
        depth[0] = 0;
        work.push(0);
        int max = 0;
        while (!work.isEmpty()) {
            int pc = work.pop();
            int sp = depth[pc];
            Op op = Op.decode(code[pc]);
            int pops;
            int pushes;
            switch (op) {
                case PUSH, LOAD -> {
                    pops = 0;
                    pushes = 1;
                }
                case POP, STORE, JZ, PRINT, RET -> {
                    pops = 1;
                    pushes = 0;
                }
                case NEG, NOT -> {
                    pops = 1;
                    pushes = 1;
                }
                case JMP -> {
                    pops = 0;
                    pushes = 0;
                }
                case CALL -> {
                    long target = code[pc + 1];
                    long argc = code[pc + 2];
                    if (target < 1 || target >= b.functions().size()) {
                        throw fail(fn, "call to unknown function " + target + " at " + pc);
                    }
                    Function callee = b.functions().get((int) target);
                    if (argc != callee.arity()) {
                        throw fail(fn, "arity mismatch calling " + callee.name() + " at " + pc);
                    }
                    pops = callee.arity();
                    pushes = 1;
                }
                default -> {
                    pops = 2;
                    pushes = 1;
                }
            }
            if (op == Op.LOAD || op == Op.STORE) {
                long slot = code[pc + 1];
                if (slot < 0 || slot >= f.nLocals()) {
                    throw fail(fn, "slot " + slot + " out of range at " + pc);
                }
            }
            if (sp < pops) {
                throw fail(fn, "stack underflow at " + pc);
            }
            if (op == Op.RET && sp != 1) {
                throw fail(fn, "return needs exactly one value on the stack at " + pc + " (depth " + sp + ")");
            }
            int after = sp - pops + pushes;
            max = Math.max(max, Math.max(sp, after));
            int next = pc + 1 + op.operands;
            if (op != Op.RET && op != Op.JMP) {
                flow(fn, code, depth, work, boundary, next, after, pc);
            }
            if (op == Op.JMP || op == Op.JZ) {
                long target = code[pc + 1];
                if (target < 0 || target >= code.length || !boundary[(int) target]) {
                    throw fail(fn, "bad jump target " + target + " at " + pc);
                }
                flow(fn, code, depth, work, boundary, (int) target, after, pc);
            }
        }
        return max;
    }

    private static void flow(String fn, long[] code, int[] depth, Deque<Integer> work, boolean[] boundary,
                             int to, int sp, int from) {
        if (to >= code.length) {
            throw fail(fn, "code falls off the end after " + from);
        }
        if (depth[to] == -1) {
            depth[to] = sp;
            work.push(to);
        } else if (depth[to] != sp) {
            throw fail(fn, "inconsistent stack depth at " + to + ": " + depth[to] + " vs " + sp);
        }
    }
}
