package ae.classfile;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract interpretation of a method body that tracks operand-stack depth in slots.
 *
 * <p>Fixed effects come from {@link Opcodes#effect}; field, invoke, ldc and multianewarray effects
 * come from the constant pool. Depths must agree wherever control flow merges, exception handlers
 * start at depth 1, and unreachable code is ignored. jsr/ret are not supported.
 */
public final class CodeAnalyzer {
    private final ClassFile cf;
    private final ConstantPool pool;
    private final List<Instruction> ins;
    private final Map<Integer, Integer> indexOf = new HashMap<>();
    private final int[] depth;
    private final Deque<Integer> work = new ArrayDeque<>();
    private int maxStack;
    private int locals;

    private CodeAnalyzer(ClassFile cf, List<Instruction> ins, int initialLocals) {
        this.cf = cf;
        this.pool = cf.pool();
        this.ins = ins;
        this.depth = new int[ins.size()];
        Arrays.fill(depth, -1);
        this.locals = initialLocals;
        for (int i = 0; i < ins.size(); i++) {
            indexOf.put(ins.get(i).offset(), i);
        }
    }

    public static Analysis analyze(ClassFile cf, Member method) {
        CodeAttr code = cf.code(method).orElseThrow(() -> new ClassFormatException("method " + method.name() + " has no code"));
        List<Instruction> ins = Disassembler.decode(code.code());
        Disassembler.validateTargets(ins, code.code().length);
        MethodDescriptor md = Descriptor.parseMethod(method.descriptor());
        int initial = md.argSlots() + ((method.access() & ClassFile.ACC_STATIC) == 0 ? 1 : 0);
        CodeAnalyzer a = new CodeAnalyzer(cf, ins, initial);
        return a.run(code);
    }

    private Analysis run(CodeAttr code) {
        merge(0, 0);
        drain();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ExceptionEntry h : code.handlers()) {
                Integer target = indexOf.get(h.handler());
                if (target == null) {
                    throw new ClassFormatException("exception handler " + h.handler() + " is not an instruction start");
                }
                if (coversReachable(h, code.code().length) && depth[target] != 1) {
                    merge(target, 1);
                    drain();
                    changed = true;
                }
            }
        }
        int reachable = 0;
        for (int d : depth) {
            if (d >= 0) {
                reachable++;
            }
        }
        return new Analysis(maxStack, locals, reachable);
    }

    private boolean coversReachable(ExceptionEntry h, int codeLength) {
        if (!indexOf.containsKey(h.start()) || (h.end() != codeLength && !indexOf.containsKey(h.end()))) {
            throw new ClassFormatException("exception table range " + h.start() + ".." + h.end()
                + " does not align with instructions");
        }
        for (int i = indexOf.get(h.start()); i < ins.size() && ins.get(i).offset() < h.end(); i++) {
            if (depth[i] >= 0) {
                return true;
            }
        }
        return false;
    }

    private void merge(int index, int d) {
        if (depth[index] == -1) {
            depth[index] = d;
            maxStack = Math.max(maxStack, d);
            work.push(index);
        } else if (depth[index] != d) {
            throw new ClassFormatException("inconsistent stack depth at offset " + ins.get(index).offset()
                + ": " + depth[index] + " vs " + d);
        }
    }

    private void drain() {
        while (!work.isEmpty()) {
            step(work.pop());
        }
    }

    private void step(int index) {
        Instruction in = ins.get(index);
        int op = in.opcode();
        int before = depth[index];
        if (op == Opcodes.JSR || op == Opcodes.RET || op == Opcodes.JSR_W) {
            throw new ClassFormatException("jsr/ret are not supported (offset " + in.offset() + ")");
        }
        int pops;
        int pushes;
        int[] fixed = Opcodes.effect(op);
        if (fixed != null) {
            pops = fixed[0];
            pushes = fixed[1];
        } else {
            int[] e = poolEffect(in);
            pops = e[0];
            pushes = e[1];
        }
        trackLocals(in);
        if (before < pops) {
            throw new ClassFormatException("stack underflow at offset " + in.offset() + " (" + in.mnemonic()
                + " needs " + pops + ", has " + before + ")");
        }
        int after = before - pops + pushes;
        maxStack = Math.max(maxStack, after);
        boolean falls = true;
        switch (Opcodes.kind(op)) {
            case BRANCH -> {
                merge(indexOf.get(in.targets()[0]), after);
                falls = op != Opcodes.GOTO && op != Opcodes.GOTO_W;
            }
            case SWITCH -> {
                for (int t : in.targets()) {
                    merge(indexOf.get(t), after);
                }
                falls = false;
            }
            default -> {
                if (op >= 0xac && op <= 0xb1 || op == Opcodes.ATHROW) {
                    falls = false;
                }
            }
        }
        if (falls) {
            if (index + 1 >= ins.size()) {
                throw new ClassFormatException("code falls off the end after offset " + in.offset());
            }
            merge(index + 1, after);
        }
    }

    private void trackLocals(Instruction in) {
        int op = in.opcode();
        boolean touches = (op >= 0x15 && op <= 0x19) || (op >= 0x1a && op <= 0x2d) || (op >= 0x36 && op <= 0x3a)
            || (op >= 0x3b && op <= 0x4e) || op == Opcodes.IINC;
        if (touches) {
            locals = Math.max(locals, in.operand() + Opcodes.localSize(op));
        }
    }

    private int[] poolEffect(Instruction in) {
        int op = in.opcode();
        switch (op) {
            case Opcodes.LDC, Opcodes.LDC_W -> {
                Constant c = pool.get(in.operand());
                return new int[] {0, switch (c) {
                    case Constant.IntegerC x -> 1;
                    case Constant.FloatC x -> 1;
                    case Constant.StringRef x -> 1;
                    case Constant.ClassRef x -> 1;
                    case Constant.MethodType x -> 1;
                    case Constant.MethodHandle x -> 1;
                    case Constant.Dynamic x -> Descriptor.parseField(pool.memberRef(in.operand()).descriptor()).slots();
                    case Constant.LongC x -> throw new ClassFormatException(in.mnemonic() + " of a long constant at " + in.offset());
                    case Constant.DoubleC x -> throw new ClassFormatException(in.mnemonic() + " of a double constant at " + in.offset());
                    default -> throw new ClassFormatException("bad ldc constant at #" + in.operand() + " (offset " + in.offset() + ")");
                }};
            }
            case Opcodes.LDC2_W -> {
                Constant c = pool.get(in.operand());
                boolean ok = c instanceof Constant.LongC || c instanceof Constant.DoubleC
                    || (c instanceof Constant.Dynamic && Descriptor.parseField(pool.memberRef(in.operand()).descriptor()).slots() == 2);
                if (!ok) {
                    throw new ClassFormatException("ldc2_w needs a long or double constant at offset " + in.offset());
                }
                return new int[] {0, 2};
            }
            case Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD -> {
                if (!(pool.get(in.operand()) instanceof Constant.FieldRef)) {
                    throw pool.wrong(in.operand(), "Fieldref");
                }
                int size = Descriptor.parseField(pool.memberRef(in.operand()).descriptor()).slots();
                return switch (op) {
                    case Opcodes.GETSTATIC -> new int[] {0, size};
                    case Opcodes.PUTSTATIC -> new int[] {size, 0};
                    case Opcodes.GETFIELD -> new int[] {1, size};
                    default -> new int[] {1 + size, 0};
                };
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE,
                 Opcodes.INVOKEDYNAMIC -> {
                Constant c = pool.get(in.operand());
                boolean ok = switch (op) {
                    case Opcodes.INVOKEVIRTUAL -> c instanceof Constant.MethodRef;
                    case Opcodes.INVOKEINTERFACE -> c instanceof Constant.InterfaceMethodRef;
                    case Opcodes.INVOKEDYNAMIC -> c instanceof Constant.InvokeDynamic;
                    default -> c instanceof Constant.MethodRef || c instanceof Constant.InterfaceMethodRef;
                };
                if (!ok) {
                    throw pool.wrong(in.operand(), "a method reference suitable for " + in.mnemonic());
                }
                MethodDescriptor md = Descriptor.parseMethod(pool.memberRef(in.operand()).descriptor());
                boolean hasReceiver = op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEDYNAMIC;
                int args = md.argSlots();
                if (op == Opcodes.INVOKEINTERFACE && in.operand2() != args + 1) {
                    throw new ClassFormatException("invokeinterface count " + in.operand2() + " should be " + (args + 1)
                        + " at offset " + in.offset());
                }
                return new int[] {args + (hasReceiver ? 1 : 0), md.ret().slots()};
            }
            case Opcodes.MULTIANEWARRAY -> {
                String cls = pool.className(in.operand());
                int rank = 0;
                while (rank < cls.length() && cls.charAt(rank) == '[') {
                    rank++;
                }
                if (in.operand2() > rank) {
                    throw new ClassFormatException("multianewarray dimensions " + in.operand2() + " exceed array rank "
                        + rank + " at offset " + in.offset());
                }
                return new int[] {in.operand2(), 1};
            }
            default -> throw new ClassFormatException("no stack effect for " + in.mnemonic());
        }
    }
}
