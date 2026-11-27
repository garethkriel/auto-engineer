package ae.wasm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates one function body with the specification's stack-polymorphic algorithm while lowering it to a flat
 * {@code int[]} program. Internal program format (opcodes are the wasm opcodes unless noted):
 * <pre>
 *   br / br_if        : op, target, arity, height
 *   br_table          : 0x0E, n, then n+1 triples (target, arity, height); the last triple is the default
 *   if                : 0x04, elseTarget   (jump when the condition is zero)
 *   else (internal)   : JMP, endTarget
 *   return            : 0x0F
 *   local/global/call : op, index
 *   load / store      : op, offset
 *   i32.const         : op, value ; i64.const : op, low, high
 * </pre>
 * {@code height} is the operand-stack height (relative to the frame base) at entry of the target block.
 */
final class FunctionCompiler {
    static final int JMP = 0xFE;

    private static final int FUNC = 0;
    private static final int BLOCK = 1;
    private static final int LOOP = 2;
    private static final int IF = 3;
    private static final int ELSE = 4;

    private static final class Frame {
        int kind;
        final ValType result;
        final int height;
        boolean unreachable;
        int loopStart;
        int elsePatch = -1;
        final List<Integer> patches = new ArrayList<>();

        Frame(int kind, ValType result, int height) {
            this.kind = kind;
            this.result = result;
            this.height = height;
        }
    }

    private final Module mod;
    private final Reader in;
    private final ValType[] locals;
    private final FuncType type;
    private final List<ValType> vals = new ArrayList<>(); // null entries are the polymorphic "unknown" type
    private final List<Frame> ctrls = new ArrayList<>();
    private int[] code = new int[64];
    private int n;
    private int maxStack;

    private FunctionCompiler(Module mod, FuncType type, ValType[] declared, Reader in) {
        this.mod = mod;
        this.in = in;
        this.type = type;
        this.locals = new ValType[type.params().size() + declared.length];
        for (int i = 0; i < type.params().size(); i++) {
            locals[i] = type.params().get(i);
        }
        System.arraycopy(declared, 0, locals, type.params().size(), declared.length);
    }

    static Compiled compile(Module mod, int funcIndex, ValType[] declared, Reader body) {
        FuncType t = mod.funcType(funcIndex);
        FunctionCompiler c = new FunctionCompiler(mod, t, declared, body);
        c.run();
        return new Compiled(Arrays.copyOf(c.code, c.n), c.maxStack, t.params().size(),
            t.result() == null ? 0 : 1, c.locals.length);
    }

    // ------------------------------------------------------------ code buffer

    private void emit(int v) {
        if (n == code.length) {
            code = Arrays.copyOf(code, n * 2);
        }
        code[n++] = v;
    }

    // ------------------------------------------------------------ type stack

    private Frame top() {
        return ctrls.get(ctrls.size() - 1);
    }

    private void push(ValType t) {
        vals.add(t);
        maxStack = Math.max(maxStack, vals.size());
    }

    private ValType pop() {
        Frame f = top();
        if (vals.size() == f.height) {
            if (f.unreachable) {
                return null;
            }
            throw Reader.invalid("stack underflow");
        }
        return vals.remove(vals.size() - 1);
    }

    /** Pops a value that must have type {@code expect}; an unknown (polymorphic) value matches anything. */
    private ValType pop(ValType expect) {
        ValType actual = pop();
        if (actual != null && expect != null && actual != expect) {
            throw Reader.invalid("type mismatch: expected " + expect + " but found " + actual);
        }
        return actual != null ? actual : expect;
    }

    private void setUnreachable() {
        Frame f = top();
        while (vals.size() > f.height) {
            vals.remove(vals.size() - 1);
        }
        f.unreachable = true;
    }

    private void unary(ValType in1, ValType out) {
        pop(in1);
        push(out);
    }

    private void binary(ValType in1, ValType out) {
        pop(in1);
        pop(in1);
        push(out);
    }

    // ------------------------------------------------------------ control helpers

    private ValType blockType() {
        int b = in.peek();
        if (b == 0x40) {
            in.u8();
            return null;
        }
        if (b == ValType.I32.code) {
            in.u8();
            return ValType.I32;
        }
        if (b == ValType.I64.code) {
            in.u8();
            return ValType.I64;
        }
        throw in.malformed("unsupported block type");
    }

    private Frame label(int depth) {
        if (depth >= ctrls.size()) {
            throw Reader.invalid("unknown label " + depth);
        }
        return ctrls.get(ctrls.size() - 1 - depth);
    }

    /** The value type a branch to {@code f} carries (a loop label carries nothing). */
    private static ValType labelType(Frame f) {
        return f.kind == LOOP ? null : f.result;
    }

    private void emitTarget(Frame f) {
        if (f.kind == LOOP) {
            emit(f.loopStart);
        } else {
            f.patches.add(n);
            emit(-1);
        }
        emit(labelType(f) == null ? 0 : 1);
        emit(f.height);
    }

    private void popResult(Frame f) {
        if (f.result != null) {
            pop(f.result);
        }
        if (vals.size() != f.height) {
            throw Reader.invalid("stack height mismatch at end of block");
        }
    }

    private void closeFrame() {
        Frame f = top();
        popResult(f);
        if (f.kind == IF && f.result != null) {
            throw Reader.invalid("if without else cannot have a result");
        }
        ctrls.remove(ctrls.size() - 1);
        if (f.kind == IF) {
            code[f.elsePatch] = n;
        }
        for (int p : f.patches) {
            code[p] = n;
        }
        if (f.kind == FUNC) {
            emit(0x0F);
        } else if (f.result != null) {
            push(f.result);
        }
    }

    // ------------------------------------------------------------ main loop

    private void run() {
        ctrls.add(new Frame(FUNC, type.result(), 0));
        while (!ctrls.isEmpty()) {
            instruction(in.u8());
        }
        if (!in.eof()) {
            throw in.malformed("operators remaining after end of function");
        }
    }

    private int localIndex() {
        int i = in.index();
        if (i >= locals.length) {
            throw Reader.invalid("unknown local " + i);
        }
        return i;
    }

    private void needMemory() {
        if (!mod.hasMemory) {
            throw Reader.invalid("unknown memory 0");
        }
    }

    private int memarg(int naturalLog2) {
        needMemory();
        long align = in.leb(32, false);
        long offset = in.leb(32, false);
        if (align > naturalLog2) {
            throw Reader.invalid("alignment must not be larger than natural");
        }
        return (int) offset;
    }

    private void load(int op, ValType result, int naturalLog2) {
        int off = memarg(naturalLog2);
        pop(ValType.I32);
        push(result);
        emit(op);
        emit(off);
    }

    private void store(int op, ValType value, int naturalLog2) {
        int off = memarg(naturalLog2);
        pop(value);
        pop(ValType.I32);
        emit(op);
        emit(off);
    }

    private void instruction(int op) {
        switch (op) {
            case 0x00 -> {
                emit(op);
                setUnreachable();
            }
            case 0x01 -> { }
            case 0x02 -> {
                ctrls.add(new Frame(BLOCK, blockType(), vals.size()));
            }
            case 0x03 -> {
                Frame f = new Frame(LOOP, blockType(), vals.size());
                f.loopStart = n;
                ctrls.add(f);
            }
            case 0x04 -> {
                ValType bt = blockType();
                pop(ValType.I32);
                Frame f = new Frame(IF, bt, vals.size());
                emit(0x04);
                f.elsePatch = n;
                emit(-1);
                ctrls.add(f);
            }
            case 0x05 -> {
                Frame f = top();
                if (f.kind != IF) {
                    throw Reader.invalid("else without if");
                }
                popResult(f);
                emit(JMP);
                f.patches.add(n);
                emit(-1);
                code[f.elsePatch] = n;
                f.kind = ELSE;
                f.unreachable = false;
            }
            case 0x0B -> closeFrame();
            case 0x0C -> {
                Frame f = label(in.index());
                ValType lt = labelType(f);
                if (lt != null) {
                    pop(lt);
                }
                emit(0x0C);
                emitTarget(f);
                setUnreachable();
            }
            case 0x0D -> {
                Frame f = label(in.index());
                pop(ValType.I32);
                ValType lt = labelType(f);
                if (lt != null) {
                    pop(lt);
                    push(lt);
                }
                emit(0x0D);
                emitTarget(f);
            }
            case 0x0E -> brTable();
            case 0x0F -> {
                if (type.result() != null) {
                    pop(type.result());
                }
                emit(0x0F);
                setUnreachable();
            }
            case 0x10 -> {
                int idx = in.index();
                if (idx >= mod.funcTypes.size()) {
                    throw Reader.invalid("unknown function " + idx);
                }
                FuncType ft = mod.funcType(idx);
                for (int i = ft.params().size() - 1; i >= 0; i--) {
                    pop(ft.params().get(i));
                }
                if (ft.result() != null) {
                    push(ft.result());
                }
                emit(0x10);
                emit(idx);
            }
            case 0x1A -> {
                pop();
                emit(0x1A);
            }
            case 0x1B -> {
                pop(ValType.I32);
                ValType t2 = pop();
                ValType t1 = pop(t2);
                push(t1 != null ? t1 : t2);
                emit(0x1B);
            }
            case 0x20 -> {
                int i = localIndex();
                push(locals[i]);
                emit(0x20);
                emit(i);
            }
            case 0x21 -> {
                int i = localIndex();
                pop(locals[i]);
                emit(0x21);
                emit(i);
            }
            case 0x22 -> {
                int i = localIndex();
                pop(locals[i]);
                push(locals[i]);
                emit(0x22);
                emit(i);
            }
            case 0x23 -> {
                int i = globalIndex();
                push(mod.globalTypes.get(i));
                emit(0x23);
                emit(i);
            }
            case 0x24 -> {
                int i = globalIndex();
                if (!mod.globalMutable.get(i)) {
                    throw Reader.invalid("immutable global " + i);
                }
                pop(mod.globalTypes.get(i));
                emit(0x24);
                emit(i);
            }
            case 0x28 -> load(op, ValType.I32, 2);
            case 0x29 -> load(op, ValType.I64, 3);
            case 0x2C, 0x2D -> load(op, ValType.I32, 0);
            case 0x2E, 0x2F -> load(op, ValType.I32, 1);
            case 0x36 -> store(op, ValType.I32, 2);
            case 0x37 -> store(op, ValType.I64, 3);
            case 0x3A -> store(op, ValType.I32, 0);
            case 0x3B -> store(op, ValType.I32, 1);
            case 0x3F, 0x40 -> {
                int reserved = in.u8();
                if (reserved != 0) {
                    throw in.malformed("zero byte expected");
                }
                needMemory();
                if (op == 0x40) {
                    pop(ValType.I32);
                }
                push(ValType.I32);
                emit(op);
            }
            case 0x41 -> {
                long v = in.leb(32, true);
                push(ValType.I32);
                emit(0x41);
                emit((int) v);
            }
            case 0x42 -> {
                long v = in.leb(64, true);
                push(ValType.I64);
                emit(0x42);
                emit((int) v);
                emit((int) (v >>> 32));
            }
            default -> numeric(op);
        }
    }

    private int globalIndex() {
        int i = in.index();
        if (i >= mod.globalTypes.size()) {
            throw Reader.invalid("unknown global " + i);
        }
        return i;
    }

    private void brTable() {
        int count = in.vecCount();
        int[] depths = new int[count + 1];
        for (int i = 0; i < count; i++) {
            depths[i] = in.index();
        }
        depths[count] = in.index();
        pop(ValType.I32);
        Frame dflt = label(depths[count]);
        ValType lt = labelType(dflt);
        for (int i = 0; i < count; i++) {
            if (labelType(label(depths[i])) != lt) {
                throw Reader.invalid("type mismatch: br_table labels have different types");
            }
        }
        if (lt != null) {
            pop(lt);
        }
        emit(0x0E);
        emit(count);
        for (int d : depths) {
            emitTarget(label(d));
        }
        setUnreachable();
    }

    private void numeric(int op) {
        if (op == 0x45) {
            unary(ValType.I32, ValType.I32);
        } else if (op >= 0x46 && op <= 0x4F) {
            binary(ValType.I32, ValType.I32);
        } else if (op == 0x50) {
            unary(ValType.I64, ValType.I32);
        } else if (op >= 0x51 && op <= 0x5A) {
            binary(ValType.I64, ValType.I32);
        } else if (op >= 0x67 && op <= 0x69) {
            unary(ValType.I32, ValType.I32);
        } else if (op >= 0x6A && op <= 0x78) {
            binary(ValType.I32, ValType.I32);
        } else if (op >= 0x79 && op <= 0x7B) {
            unary(ValType.I64, ValType.I64);
        } else if (op >= 0x7C && op <= 0x8A) {
            binary(ValType.I64, ValType.I64);
        } else if (op == 0xA7) {
            unary(ValType.I64, ValType.I32);
        } else if (op == 0xAC || op == 0xAD) {
            unary(ValType.I32, ValType.I64);
        } else if (op == 0xC0 || op == 0xC1) {
            unary(ValType.I32, ValType.I32);
        } else if (op >= 0xC2 && op <= 0xC4) {
            unary(ValType.I64, ValType.I64);
        } else {
            throw in.malformed(String.format("illegal opcode 0x%02x", op));
        }
        emit(op);
    }
}
