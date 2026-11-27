package ae.wasm;

import java.util.Arrays;
import java.util.Objects;

/**
 * An instantiated module: memory, globals and an interpreter. Values are one {@code long} each; an {@code i32} is
 * stored sign-extended. Not thread-safe.
 */
public final class Instance {
    private static final int PAGE = 65_536;
    private static final int MAX_PAGES = 256;
    private static final int MAX_DEPTH = 300;
    private static final int STACK_SLOTS = 1 << 16;

    private final Module module;
    private byte[] mem;
    private final long[] globals;
    private final long[] stack = new long[STACK_SLOTS];
    private long fuel;
    private int depth;

    private Instance(Module module, long fuel) {
        this.module = module;
        this.fuel = fuel;
        this.globals = new long[module.globalTypes.size()];
        for (int i = 0; i < globals.length; i++) {
            globals[i] = module.globalInit.get(i);
        }
    }

    /** Instantiates with an unlimited fuel budget. */
    public static Instance instantiate(Module module) {
        return instantiate(module, Long.MAX_VALUE);
    }

    /** Instantiates with the given fuel budget (one unit per taken branch and per call). */
    public static Instance instantiate(Module module, long fuel) {
        Objects.requireNonNull(module, "module");
        Instance inst = new Instance(module, fuel);
        if (module.hasMemory) {
            if (module.memMin > MAX_PAGES) {
                throw new ModuleException(ModuleException.Kind.LINK, "memory too large: " + module.memMin + " pages");
            }
            inst.mem = new byte[module.memMin * PAGE];
        } else {
            inst.mem = new byte[0];
        }
        for (Module.DataSegment d : module.data) {
            if (d.offset() + d.bytes().length > inst.mem.length) {
                throw new ModuleException(ModuleException.Kind.LINK, "data segment does not fit");
            }
        }
        for (Module.DataSegment d : module.data) {
            System.arraycopy(d.bytes(), 0, inst.mem, (int) d.offset(), d.bytes().length);
        }
        if (module.start >= 0) {
            inst.depth = 0;
            try {
                inst.exec(module.start, 0);
            } finally {
                inst.depth = 0;
            }
        }
        return inst;
    }

    /** Current memory size in pages (0 without a memory). */
    public int memoryPages() {
        return mem.length / PAGE;
    }

    /** Copies {@code len} bytes of linear memory starting at {@code addr}. */
    public byte[] readMemory(int addr, int len) {
        if (addr < 0 || len < 0 || (long) addr + len > mem.length) {
            throw new IllegalArgumentException("range outside memory");
        }
        return Arrays.copyOfRange(mem, addr, addr + len);
    }

    /** Value of an exported global (i32 sign-extended). */
    public long global(String name) {
        Module.Export e = module.exports.get(name);
        if (e == null || e.kind() != 3) {
            throw new IllegalArgumentException("no exported global " + name);
        }
        return globals[e.index()];
    }

    /** Calls an exported function. */
    public long[] invoke(String name, long... args) {
        Module.Export e = module.exports.get(name);
        if (e == null || e.kind() != 0) {
            throw new IllegalArgumentException("no exported function " + name);
        }
        FuncType t = module.funcType(e.index());
        if (args.length != t.params().size()) {
            throw new IllegalArgumentException("expected " + t.params().size() + " arguments but got " + args.length);
        }
        for (int i = 0; i < args.length; i++) {
            stack[i] = t.params().get(i) == ValType.I32 ? (long) (int) args[i] : args[i];
        }
        depth = 0;
        try {
            exec(e.index(), args.length);
        } finally {
            depth = 0;
        }
        return t.result() == null ? new long[0] : new long[] {stack[0]};
    }

    private static Trap trap(Trap.Kind k) {
        return new Trap(k);
    }

    private void burn() {
        if (--fuel < 0) {
            throw trap(Trap.Kind.OUT_OF_FUEL);
        }
    }

    // ------------------------------------------------------------ memory access

    private long load(long ea, int size) {
        if (ea + size > mem.length) {
            throw trap(Trap.Kind.OUT_OF_BOUNDS);
        }
        long v = 0;
        for (int i = size - 1; i >= 0; i--) {
            v = (v << 8) | (mem[(int) ea + i] & 0xFFL);
        }
        return v;
    }

    private void store(long ea, int size, long v) {
        if (ea + size > mem.length) {
            throw trap(Trap.Kind.OUT_OF_BOUNDS);
        }
        for (int i = 0; i < size; i++) {
            mem[(int) ea + i] = (byte) (v >>> (8 * i));
        }
    }

    private static long ea(long base, int offset) {
        return (base & 0xFFFFFFFFL) + Integer.toUnsignedLong(offset);
    }

    private long grow(int deltaPages) {
        long old = mem.length / PAGE;
        long limit = module.memMax >= 0 ? Math.min(module.memMax, MAX_PAGES) : MAX_PAGES;
        long want = old + Integer.toUnsignedLong(deltaPages);
        if (want > limit) {
            return -1;
        }
        mem = Arrays.copyOf(mem, (int) (want * PAGE));
        return old;
    }

    // ------------------------------------------------------------ interpreter

    /**
     * Runs function {@code fn} whose arguments are the top {@code nParams} slots below {@code sp}; returns the new
     * stack pointer with the result (if any) in place of the arguments.
     */
    private int exec(int fn, int sp) {
        Compiled f = module.compiled[fn];
        if (++depth > MAX_DEPTH) {
            throw trap(Trap.Kind.STACK_EXHAUSTED);
        }
        try {
            return run(f, sp);
        } finally {
            depth--;
        }
    }

    private int run(Compiled f, int entrySp) {
        final int base = entrySp - f.nParams();
        if ((long) base + f.maxStack() > stack.length) {
            throw trap(Trap.Kind.STACK_EXHAUSTED);
        }
        final long[] st = stack;
        final int[] code = f.code();
        final long[] locals = new long[f.nLocals()];
        System.arraycopy(st, base, locals, 0, f.nParams());
        int sp = base;
        int pc = 0;
        while (true) {
            int op = code[pc++];
            switch (op) {
                case 0x00 -> throw trap(Trap.Kind.UNREACHABLE);
                case 0x04 -> {
                    int cond = (int) st[--sp];
                    pc = cond != 0 ? pc + 1 : code[pc];
                }
                case FunctionCompiler.JMP -> pc = code[pc];
                case 0x0C -> {
                    burn();
                    int arity = code[pc + 1];
                    int h = base + code[pc + 2];
                    if (arity == 1) {
                        st[h] = st[sp - 1];
                    }
                    sp = h + arity;
                    pc = code[pc];
                }
                case 0x0D -> {
                    int cond = (int) st[--sp];
                    if (cond != 0) {
                        burn();
                        int arity = code[pc + 1];
                        int h = base + code[pc + 2];
                        if (arity == 1) {
                            st[h] = st[sp - 1];
                        }
                        sp = h + arity;
                        pc = code[pc];
                    } else {
                        pc += 3;
                    }
                }
                case 0x0E -> {
                    burn();
                    long idx = st[--sp] & 0xFFFFFFFFL;
                    int count = code[pc];
                    int sel = idx < count ? (int) idx : count;
                    int e = pc + 1 + sel * 3;
                    int arity = code[e + 1];
                    int h = base + code[e + 2];
                    if (arity == 1) {
                        st[h] = st[sp - 1];
                    }
                    sp = h + arity;
                    pc = code[e];
                }
                case 0x0F -> {
                    if (f.nResults() == 1) {
                        st[base] = st[sp - 1];
                    }
                    return base + f.nResults();
                }
                case 0x10 -> {
                    burn();
                    int callee = code[pc++];
                    sp = exec(callee, sp);
                }
                case 0x1A -> sp--;
                case 0x1B -> {
                    int cond = (int) st[--sp];
                    long second = st[--sp];
                    if (cond == 0) {
                        st[sp - 1] = second;
                    }
                }
                case 0x20 -> st[sp++] = locals[code[pc++]];
                case 0x21 -> locals[code[pc++]] = st[--sp];
                case 0x22 -> locals[code[pc++]] = st[sp - 1];
                case 0x23 -> st[sp++] = globals[code[pc++]];
                case 0x24 -> globals[code[pc++]] = st[--sp];
                case 0x28 -> st[sp - 1] = (int) load(ea(st[sp - 1], code[pc++]), 4);
                case 0x29 -> st[sp - 1] = load(ea(st[sp - 1], code[pc++]), 8);
                case 0x2C -> st[sp - 1] = (byte) load(ea(st[sp - 1], code[pc++]), 1);
                case 0x2D -> st[sp - 1] = load(ea(st[sp - 1], code[pc++]), 1);
                case 0x2E -> st[sp - 1] = (short) load(ea(st[sp - 1], code[pc++]), 2);
                case 0x2F -> st[sp - 1] = load(ea(st[sp - 1], code[pc++]), 2);
                case 0x36, 0x37, 0x3A, 0x3B -> {
                    long v = st[--sp];
                    long addr = ea(st[--sp], code[pc++]);
                    int size = op == 0x36 ? 4 : op == 0x37 ? 8 : op == 0x3A ? 1 : 2;
                    store(addr, size, v);
                }
                case 0x3F -> st[sp++] = mem.length / PAGE;
                case 0x40 -> st[sp - 1] = (int) grow((int) st[sp - 1]);
                case 0x41 -> st[sp++] = code[pc++];
                case 0x42 -> {
                    st[sp++] = (code[pc] & 0xFFFFFFFFL) | ((long) code[pc + 1] << 32);
                    pc += 2;
                }
                default -> sp = numeric(op, st, sp);
            }
        }
    }

    /** Executes a numeric instruction on the operand stack and returns the new stack pointer. */
    private static int numeric(int op, long[] st, int sp) {
        if (op >= 0x46 && op <= 0x4F) {
            int b = (int) st[--sp];
            int a = (int) st[sp - 1];
            st[sp - 1] = i32Cmp(op, a, b) ? 1 : 0;
        } else if (op == 0x45) {
            st[sp - 1] = (int) st[sp - 1] == 0 ? 1 : 0;
        } else if (op == 0x50) {
            st[sp - 1] = st[sp - 1] == 0 ? 1 : 0;
        } else if (op >= 0x51 && op <= 0x5A) {
            long b = st[--sp];
            long a = st[sp - 1];
            st[sp - 1] = i64Cmp(op, a, b) ? 1 : 0;
        } else if (op >= 0x67 && op <= 0x69) {
            int a = (int) st[sp - 1];
            st[sp - 1] = op == 0x67 ? Integer.numberOfLeadingZeros(a)
                : op == 0x68 ? Integer.numberOfTrailingZeros(a) : Integer.bitCount(a);
        } else if (op >= 0x6A && op <= 0x78) {
            int b = (int) st[--sp];
            int a = (int) st[sp - 1];
            st[sp - 1] = i32Bin(op, a, b);
        } else if (op >= 0x79 && op <= 0x7B) {
            long a = st[sp - 1];
            st[sp - 1] = op == 0x79 ? Long.numberOfLeadingZeros(a)
                : op == 0x7A ? Long.numberOfTrailingZeros(a) : Long.bitCount(a);
        } else if (op >= 0x7C && op <= 0x8A) {
            long b = st[--sp];
            long a = st[sp - 1];
            st[sp - 1] = i64Bin(op, a, b);
        } else {
            long a = st[sp - 1];
            st[sp - 1] = switch (op) {
                case 0xA7 -> (int) a;
                case 0xAC -> (long) (int) a;
                case 0xAD -> a & 0xFFFFFFFFL;
                case 0xC0 -> (byte) a;
                case 0xC1 -> (short) a;
                case 0xC2 -> (byte) a;
                case 0xC3 -> (short) a;
                case 0xC4 -> (int) a;
                default -> throw new IllegalStateException("unexpected opcode " + op);
            };
        }
        return sp;
    }

    private static boolean i32Cmp(int op, int a, int b) {
        return switch (op) {
            case 0x46 -> a == b;
            case 0x47 -> a != b;
            case 0x48 -> a < b;
            case 0x49 -> Integer.compareUnsigned(a, b) < 0;
            case 0x4A -> a > b;
            case 0x4B -> Integer.compareUnsigned(a, b) > 0;
            case 0x4C -> a <= b;
            case 0x4D -> Integer.compareUnsigned(a, b) <= 0;
            case 0x4E -> a >= b;
            case 0x4F -> Integer.compareUnsigned(a, b) >= 0;
            default -> throw new IllegalStateException("unexpected opcode " + op);
        };
    }

    private static boolean i64Cmp(int op, long a, long b) {
        return switch (op) {
            case 0x51 -> a == b;
            case 0x52 -> a != b;
            case 0x53 -> a < b;
            case 0x54 -> Long.compareUnsigned(a, b) < 0;
            case 0x55 -> a > b;
            case 0x56 -> Long.compareUnsigned(a, b) > 0;
            case 0x57 -> a <= b;
            case 0x58 -> Long.compareUnsigned(a, b) <= 0;
            case 0x59 -> a >= b;
            case 0x5A -> Long.compareUnsigned(a, b) >= 0;
            default -> throw new IllegalStateException("unexpected opcode " + op);
        };
    }

    private static int i32Bin(int op, int a, int b) {
        return switch (op) {
            case 0x6A -> a + b;
            case 0x6B -> a - b;
            case 0x6C -> a * b;
            case 0x6D -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                if (a == Integer.MIN_VALUE && b == -1) {
                    throw trap(Trap.Kind.INT_OVERFLOW);
                }
                yield a / b;
            }
            case 0x6E -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield Integer.divideUnsigned(a, b);
            }
            case 0x6F -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield a % b; // MIN % -1 is 0 in Java (only division by zero throws)
            }
            case 0x70 -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield Integer.remainderUnsigned(a, b);
            }
            case 0x71 -> a & b;
            case 0x72 -> a | b;
            case 0x73 -> a ^ b;
            case 0x74 -> a << b;
            case 0x75 -> a >> b;
            case 0x76 -> a >>> b;
            case 0x77 -> Integer.rotateLeft(a, b);
            case 0x78 -> Integer.rotateRight(a, b);
            default -> throw new IllegalStateException("unexpected opcode " + op);
        };
    }

    private static long i64Bin(int op, long a, long b) {
        return switch (op) {
            case 0x7C -> a + b;
            case 0x7D -> a - b;
            case 0x7E -> a * b;
            case 0x7F -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                if (a == Long.MIN_VALUE && b == -1) {
                    throw trap(Trap.Kind.INT_OVERFLOW);
                }
                yield a / b;
            }
            case 0x80 -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield Long.divideUnsigned(a, b);
            }
            case 0x81 -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield a % b; // MIN % -1 is 0 in Java (only division by zero throws)
            }
            case 0x82 -> {
                if (b == 0) {
                    throw trap(Trap.Kind.DIV_ZERO);
                }
                yield Long.remainderUnsigned(a, b);
            }
            case 0x83 -> a & b;
            case 0x84 -> a | b;
            case 0x85 -> a ^ b;
            case 0x86 -> a << b;
            case 0x87 -> a >> b;
            case 0x88 -> a >>> b;
            case 0x89 -> Long.rotateLeft(a, (int) b);
            case 0x8A -> Long.rotateRight(a, (int) b);
            default -> throw new IllegalStateException("unexpected opcode " + op);
        };
    }
}
