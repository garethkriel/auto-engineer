package ae.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Test-side WebAssembly binary writer: LEB128 encoders, a code builder and a module builder. */
final class Bin {
    private Bin() { }

    static byte[] u(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (v != 0);
        return out.toByteArray();
    }

    static byte[] s(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean more = true;
        while (more) {
            int b = (int) (v & 0x7F);
            v >>= 7;
            if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
                more = false;
            } else {
                b |= 0x80;
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    static int valType(char c) {
        return switch (c) {
            case 'i' -> 0x7F;
            case 'l' -> 0x7E;
            default -> throw new IllegalArgumentException("type char " + c);
        };
    }

    /** Fluent function-body builder (the final `end` is added by {@link M#func}). */
    static final class C {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        C b(int... v) {
            out.writeBytes(Bin.bytes(v));
            return this;
        }

        C i32(int v) { b(0x41); out.writeBytes(s(v)); return this; }
        C i64(long v) { b(0x42); out.writeBytes(s(v)); return this; }
        C get(int i) { b(0x20); out.writeBytes(u(i)); return this; }
        C set(int i) { b(0x21); out.writeBytes(u(i)); return this; }
        C tee(int i) { b(0x22); out.writeBytes(u(i)); return this; }
        C gget(int i) { b(0x23); out.writeBytes(u(i)); return this; }
        C gset(int i) { b(0x24); out.writeBytes(u(i)); return this; }
        C call(int i) { b(0x10); out.writeBytes(u(i)); return this; }
        C br(int d) { b(0x0C); out.writeBytes(u(d)); return this; }
        C brIf(int d) { b(0x0D); out.writeBytes(u(d)); return this; }

        C brTable(int dflt, int... labels) {
            b(0x0E);
            out.writeBytes(u(labels.length));
            for (int l : labels) {
                out.writeBytes(u(l));
            }
            out.writeBytes(u(dflt));
            return this;
        }

        /** Memory instruction with alignment exponent and offset. */
        C mem(int op, int align, long off) {
            b(op);
            out.writeBytes(u(align));
            out.writeBytes(u(off));
            return this;
        }

        C end() { return b(0x0B); }
        C ret() { return b(0x0F); }
        byte[] bytes() { return out.toByteArray(); }
    }

    static C c() {
        return new C();
    }

    /** Module builder. Section order is always the canonical one. */
    static final class M {
        final List<byte[]> types = new ArrayList<>();
        final List<Integer> funcTypes = new ArrayList<>();
        final List<byte[]> codes = new ArrayList<>();
        final List<byte[]> globals = new ArrayList<>();
        final List<byte[]> exports = new ArrayList<>();
        final List<byte[]> data = new ArrayList<>();
        byte[] memory;
        int start = -1;
        byte[] custom;
        /** Offsets in the built image at which a section ends (valid truncation points). */
        final List<Integer> sectionEnds = new ArrayList<>();

        /** sig "ii:i" = params "ii", results "i" ('i' = i32, 'l' = i64). */
        int type(String sig) {
            String[] pr = sig.split(":", -1);
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            t.write(0x60);
            t.writeBytes(u(pr[0].length()));
            for (char ch : pr[0].toCharArray()) {
                t.write(valType(ch));
            }
            t.writeBytes(u(pr[1].length()));
            for (char ch : pr[1].toCharArray()) {
                t.write(valType(ch));
            }
            types.add(t.toByteArray());
            return types.size() - 1;
        }

        /** Adds a function; {@code locals} has one char per declared local. Returns its function index. */
        int func(int type, String locals, C body) {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            e.writeBytes(u(locals.length()));
            for (char ch : locals.toCharArray()) {
                e.writeBytes(u(1));
                e.write(valType(ch));
            }
            e.writeBytes(body.bytes());
            e.write(0x0B);
            return funcRaw(type, e.toByteArray());
        }

        int funcRaw(int type, byte[] entry) {
            funcTypes.add(type);
            codes.add(entry);
            return funcTypes.size() - 1;
        }

        M mem(int min, int max) {
            memory = max < 0 ? cat(bytes(1, 0), u(min)) : cat(bytes(1, 1), u(min), u(max));
            return this;
        }

        int global(char ty, boolean mutable, long init) {
            globals.add(cat(bytes(valType(ty), mutable ? 1 : 0), bytes(ty == 'i' ? 0x41 : 0x42), s(init), bytes(0x0B)));
            return globals.size() - 1;
        }

        M export(String name, int kind, int idx) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            exports.add(cat(u(n.length), n, bytes(kind), u(idx)));
            return this;
        }

        M exportFunc(String name, int idx) {
            return export(name, 0, idx);
        }

        M start(int idx) {
            start = idx;
            return this;
        }

        M data(int offset, byte[] bytes) {
            data.add(cat(bytes(0, 0x41), s(offset), bytes(0x0B), u(bytes.length), bytes));
            return this;
        }

        private static byte[] vec(List<byte[]> items) {
            byte[][] parts = new byte[items.size() + 1][];
            parts[0] = u(items.size());
            for (int i = 0; i < items.size(); i++) {
                parts[i + 1] = items.get(i);
            }
            return cat(parts);
        }

        private byte[] section(int id, byte[] payload) {
            byte[] sec = cat(bytes(id), u(payload.length), payload);
            return sec;
        }

        byte[] build() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00));
            sectionEnds.clear();
            sectionEnds.add(out.size());
            List<byte[]> secs = new ArrayList<>();
            if (custom != null) {
                secs.add(section(0, custom));
            }
            if (!types.isEmpty()) {
                secs.add(section(1, vec(types)));
            }
            if (!funcTypes.isEmpty()) {
                List<byte[]> f = new ArrayList<>();
                for (int t : funcTypes) {
                    f.add(u(t));
                }
                secs.add(section(3, vec(f)));
            }
            if (memory != null) {
                secs.add(section(5, memory));
            }
            if (!globals.isEmpty()) {
                secs.add(section(6, vec(globals)));
            }
            if (!exports.isEmpty()) {
                secs.add(section(7, vec(exports)));
            }
            if (start >= 0) {
                secs.add(section(8, u(start)));
            }
            if (!codes.isEmpty()) {
                List<byte[]> cs = new ArrayList<>();
                for (byte[] e : codes) {
                    cs.add(cat(u(e.length), e));
                }
                secs.add(section(10, vec(cs)));
            }
            if (!data.isEmpty()) {
                secs.add(section(11, vec(data)));
            }
            for (byte[] s : secs) {
                out.writeBytes(s);
                sectionEnds.add(out.size());
            }
            return out.toByteArray();
        }
    }
}
