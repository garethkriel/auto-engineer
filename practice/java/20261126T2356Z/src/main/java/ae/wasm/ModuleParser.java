package ae.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decodes and validates a WebAssembly binary (the integer MVP subset described in the round's TASK.md).
 * Only {@link ModuleException} can escape.
 */
public final class ModuleParser {
    private static final int MAX_LOCALS = 50_000;
    private static final long MAX_PAGES = 65_536;

    private final byte[] bytes;
    private final Module module = new Module();
    private final List<Object[]> bodies = new ArrayList<>(); // {ValType[] locals, Reader body}

    private ModuleParser(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Parses and validates {@code bytes}. */
    public static Module parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new ModuleParser(bytes).run();
    }

    private Module run() {
        Reader r = new Reader(bytes, 0, bytes.length);
        for (int expected : new int[] {0x00, 0x61, 0x73, 0x6D}) {
            if (r.u8() != expected) {
                throw r.malformed("magic header not detected");
            }
        }
        for (int expected : new int[] {0x01, 0x00, 0x00, 0x00}) {
            if (r.u8() != expected) {
                throw r.malformed("unknown binary version");
            }
        }
        int last = 0;
        List<Object[]> exportList = new ArrayList<>();
        while (!r.eof()) {
            int id = r.u8();
            if (id > 12) {
                throw r.malformed("unknown section id " + id);
            }
            long size = r.leb(32, false);
            if (size > r.remaining()) {
                throw r.malformed("section size exceeds remaining bytes");
            }
            Reader s = new Reader(bytes, r.pos, r.pos + (int) size);
            if (id != 0) {
                if (id <= last) {
                    throw r.malformed("unexpected section order");
                }
                last = id;
            }
            switch (id) {
                case 0 -> s.name();
                case 1 -> typeSection(s);
                case 3 -> functionSection(s);
                case 5 -> memorySection(s);
                case 6 -> globalSection(s);
                case 7 -> exportSection(s, exportList);
                case 8 -> module.start = s.index();
                case 10 -> codeSection(s);
                case 11 -> dataSection(s);
                default -> throw Reader.invalid("unsupported section " + id);
            }
            if (id == 0) {
                s.pos = s.end; // custom section payload after the name is opaque
            }
            if (!s.eof()) {
                throw s.malformed("section size mismatch");
            }
            r.pos += (int) size;
        }
        if (module.funcTypes.size() != bodies.size()) {
            throw r.malformed("function and code section have inconsistent lengths");
        }
        module.compiled = new Compiled[bodies.size()];
        for (int i = 0; i < bodies.size(); i++) {
            module.compiled[i] = FunctionCompiler.compile(module, i, (ValType[]) bodies.get(i)[0],
                (Reader) bodies.get(i)[1]);
        }
        checkExports(exportList);
        if (module.start >= 0) {
            if (module.start >= module.funcTypes.size()) {
                throw Reader.invalid("unknown function " + module.start);
            }
            FuncType t = module.funcType(module.start);
            if (!t.params().isEmpty() || t.result() != null) {
                throw Reader.invalid("start function must have type [] -> []");
            }
        }
        return module;
    }

    private static ValType valType(Reader s) {
        int b = s.u8();
        if (b == ValType.I32.code) {
            return ValType.I32;
        }
        if (b == ValType.I64.code) {
            return ValType.I64;
        }
        throw s.malformed(String.format("unsupported value type 0x%02x", b));
    }

    private void typeSection(Reader s) {
        int n = s.vecCount();
        for (int i = 0; i < n; i++) {
            int form = s.u8();
            if (form != 0x60) {
                throw s.malformed(String.format("invalid function type form 0x%02x", form));
            }
            int pc = s.vecCount();
            List<ValType> params = new ArrayList<>();
            for (int p = 0; p < pc; p++) {
                params.add(valType(s));
            }
            int rc = s.vecCount();
            if (rc > 1) {
                throw Reader.invalid("multiple results are not supported");
            }
            ValType result = rc == 1 ? valType(s) : null;
            module.types.add(new FuncType(List.copyOf(params), result));
        }
    }

    private void functionSection(Reader s) {
        int n = s.vecCount();
        for (int i = 0; i < n; i++) {
            int t = s.index();
            if (t >= module.types.size()) {
                throw Reader.invalid("unknown type " + t);
            }
            module.funcTypes.add(t);
        }
    }

    private void memorySection(Reader s) {
        int n = s.vecCount();
        if (n > 1) {
            throw Reader.invalid("multiple memories");
        }
        if (n == 1) {
            int flag = s.u8();
            if (flag != 0 && flag != 1) {
                throw s.malformed("invalid limits flag " + flag);
            }
            long min = s.leb(32, false);
            long max = flag == 1 ? s.leb(32, false) : -1;
            if (min > MAX_PAGES || max > MAX_PAGES) {
                throw Reader.invalid("memory size must be at most 65536 pages (4GiB)");
            }
            if (flag == 1 && max < min) {
                throw Reader.invalid("size minimum must not be greater than maximum");
            }
            module.hasMemory = true;
            module.memMin = (int) min;
            module.memMax = max;
        }
    }

    /** Reads {@code i32.const|i64.const v end} and checks it against {@code expected}. */
    private static long constExpr(Reader s, ValType expected) {
        int op = s.u8();
        long v;
        ValType t;
        if (op == 0x41) {
            v = s.leb(32, true);
            t = ValType.I32;
        } else if (op == 0x42) {
            v = s.leb(64, true);
            t = ValType.I64;
        } else {
            throw Reader.invalid(String.format("unsupported constant expression opcode 0x%02x", op));
        }
        int end = s.u8();
        if (end != 0x0B) {
            throw s.malformed("constant expression is missing its end marker");
        }
        if (t != expected) {
            throw Reader.invalid("constant expression type mismatch");
        }
        return v;
    }

    private void globalSection(Reader s) {
        int n = s.vecCount();
        for (int i = 0; i < n; i++) {
            ValType t = valType(s);
            int mut = s.u8();
            if (mut != 0 && mut != 1) {
                throw s.malformed("invalid mutability " + mut);
            }
            module.globalTypes.add(t);
            module.globalMutable.add(mut == 1);
            module.globalInit.add(constExpr(s, t));
        }
    }

    private void exportSection(Reader s, List<Object[]> out) {
        int n = s.vecCount();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            String name = s.name();
            int kind = s.u8();
            int idx = s.index();
            if (kind == 1) {
                throw Reader.invalid("unsupported export kind 1 (table)");
            }
            if (kind > 3) {
                throw s.malformed("malformed export kind " + kind);
            }
            if (!seen.add(name)) {
                throw Reader.invalid("duplicate export name " + name);
            }
            out.add(new Object[] {name, kind, idx});
        }
    }

    private void checkExports(List<Object[]> list) {
        for (Object[] e : list) {
            String name = (String) e[0];
            int kind = (Integer) e[1];
            int idx = (Integer) e[2];
            switch (kind) {
                case 0 -> {
                    if (idx >= module.funcTypes.size()) {
                        throw Reader.invalid("unknown function " + idx);
                    }
                }
                case 2 -> {
                    if (!module.hasMemory || idx != 0) {
                        throw Reader.invalid("unknown memory " + idx);
                    }
                }
                default -> {
                    if (idx >= module.globalTypes.size()) {
                        throw Reader.invalid("unknown global " + idx);
                    }
                }
            }
            module.exports.put(name, new Module.Export(kind, idx));
        }
    }

    private void codeSection(Reader s) {
        int n = s.vecCount();
        for (int i = 0; i < n; i++) {
            long size = s.leb(32, false);
            if (size > s.remaining()) {
                throw s.malformed("function body size exceeds remaining bytes");
            }
            Reader b = new Reader(bytes, s.pos, s.pos + (int) size);
            s.pos += (int) size;
            int groups = b.vecCount();
            List<ValType> locals = new ArrayList<>();
            long total = 0;
            for (int g = 0; g < groups; g++) {
                long count = b.leb(32, false);
                ValType t = valType(b);
                total += count;
                if (total > MAX_LOCALS) {
                    throw Reader.invalid("too many locals");
                }
                for (long k = 0; k < count; k++) {
                    locals.add(t);
                }
            }
            bodies.add(new Object[] {locals.toArray(new ValType[0]), b});
        }
    }

    private void dataSection(Reader s) {
        int n = s.vecCount();
        for (int i = 0; i < n; i++) {
            long flag = s.leb(32, false);
            if (flag != 0) {
                throw s.malformed("unsupported data segment flag " + flag);
            }
            if (!module.hasMemory) {
                throw Reader.invalid("unknown memory 0");
            }
            long offset = constExpr(s, ValType.I32);
            int len = s.vecCount();
            module.data.add(new Module.DataSegment(offset & 0xFFFFFFFFL, s.bytes(len)));
        }
    }
}
