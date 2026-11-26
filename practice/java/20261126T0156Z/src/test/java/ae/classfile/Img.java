package ae.classfile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Test-side class-file builder, written independently of {@code ClassWriter}.
 * Defaults describe {@code T extends java/lang/Object} with no members; every
 * defect test starts from a valid image and changes exactly one thing.
 */
final class Img {
    static final int PUBLIC = 1, STATIC = 8, FINAL = 0x10, NATIVE = 0x100, ABSTRACT = 0x400, MODULE = 0x8000;

    int magic = 0xCAFEBABE;
    int minor = 0;
    int major = 65;
    int access = 0x21;
    int thisIdx;
    int superIdx;
    int[] interfaces = {};
    byte[] trailing = {};
    private final ByteArrayOutputStream pool = new ByteArrayOutputStream();
    private final DataOutputStream poolOut = new DataOutputStream(pool);
    private int poolCount = 1;
    private final Map<String, Integer> cache = new HashMap<>();
    private final ByteArrayOutputStream fields = new ByteArrayOutputStream();
    private int fieldCount;
    private final ByteArrayOutputStream methods = new ByteArrayOutputStream();
    private int methodCount;
    private final ByteArrayOutputStream attrs = new ByteArrayOutputStream();
    private int attrCount;

    Img() {
        thisIdx = cls("T");
        superIdx = cls("java/lang/Object");
    }

    /** A valid image with one {@code static void m()} whose body is a single return. */
    static Img minimal() {
        Img i = new Img();
        i.method(PUBLIC | STATIC, "m", "()V", 0, 0, new byte[] {(byte) 0xb1});
        return i;
    }

    private int add(String key, int slots, IoWriter w) {
        Integer have = cache.get(key);
        if (have != null) {
            return have;
        }
        int idx = poolCount;
        try {
            w.write(poolOut);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        poolCount += slots;
        cache.put(key, idx);
        return idx;
    }

    /** Appends raw bytes as one pool entry occupying {@code slots} indexes (no caching). */
    int raw(int slots, int... entry) {
        byte[] bytes = new byte[entry.length];
        for (int i = 0; i < entry.length; i++) {
            bytes[i] = (byte) entry[i];
        }
        int idx = poolCount;
        pool.writeBytes(bytes);
        poolCount += slots;
        return idx;
    }

    int utf8(String s) {
        return add("u" + s, 1, o -> {
            o.writeByte(1);
            o.writeShort(Modified.encode(s).length);
            o.write(Modified.encode(s));
        });
    }

    int cls(String name) {
        int n = utf8(name);
        return add("c" + n, 1, o -> {
            o.writeByte(7);
            o.writeShort(n);
        });
    }

    int string(String s) {
        int n = utf8(s);
        return add("s" + n, 1, o -> {
            o.writeByte(8);
            o.writeShort(n);
        });
    }

    int integer(int v) {
        return add("i" + v, 1, o -> {
            o.writeByte(3);
            o.writeInt(v);
        });
    }

    int longC(long v) {
        return add("j" + v, 2, o -> {
            o.writeByte(5);
            o.writeLong(v);
        });
    }

    int doubleC(long bits) {
        return add("d" + bits, 2, o -> {
            o.writeByte(6);
            o.writeLong(bits);
        });
    }

    int floatC(int bits) {
        return add("f" + bits, 1, o -> {
            o.writeByte(4);
            o.writeInt(bits);
        });
    }

    int nat(String name, String desc) {
        int n = utf8(name);
        int d = utf8(desc);
        return add("n" + n + "," + d, 1, o -> {
            o.writeByte(12);
            o.writeShort(n);
            o.writeShort(d);
        });
    }

    private int ref(int tag, String owner, String name, String desc) {
        int c = cls(owner);
        int nt = nat(name, desc);
        return add("r" + tag + "," + c + "," + nt, 1, o -> {
            o.writeByte(tag);
            o.writeShort(c);
            o.writeShort(nt);
        });
    }

    int fieldRef(String owner, String name, String desc) {
        return ref(9, owner, name, desc);
    }

    int methodRef(String owner, String name, String desc) {
        return ref(10, owner, name, desc);
    }

    int interfaceMethodRef(String owner, String name, String desc) {
        return ref(11, owner, name, desc);
    }

    int indy(int bootstrap, String name, String desc) {
        int nt = nat(name, desc);
        return add("y" + bootstrap + "," + nt, 1, o -> {
            o.writeByte(18);
            o.writeShort(bootstrap);
            o.writeShort(nt);
        });
    }

    int dynamic(int bootstrap, String name, String desc) {
        int nt = nat(name, desc);
        return add("z" + bootstrap + "," + nt, 1, o -> {
            o.writeByte(17);
            o.writeShort(bootstrap);
            o.writeShort(nt);
        });
    }

    int methodHandle(int kind, int refIdx) {
        return add("h" + kind + "," + refIdx, 1, o -> {
            o.writeByte(15);
            o.writeByte(kind);
            o.writeShort(refIdx);
        });
    }

    int methodType(String desc) {
        int d = utf8(desc);
        return add("t" + d, 1, o -> {
            o.writeByte(16);
            o.writeShort(d);
        });
    }

    /** Adds a method with a Code attribute (and optional exception table rows {start,end,handler,type}). */
    Img method(int acc, String name, String desc, int maxStack, int maxLocals, byte[] code, int[]... table) {
        return methodWith(acc, utf8(name), utf8(desc), codeAttr(maxStack, maxLocals, code, table));
    }

    byte[] codeAttr(int maxStack, int maxLocals, byte[] code, int[]... table) {
        int nameIdx = utf8("Code");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        try {
            o.writeShort(maxStack);
            o.writeShort(maxLocals);
            o.writeInt(code.length);
            o.write(code);
            o.writeShort(table.length);
            for (int[] row : table) {
                for (int v : row) {
                    o.writeShort(v);
                }
            }
            o.writeShort(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return attr(nameIdx, b.toByteArray());
    }

    static byte[] attr(int nameIdx, byte[] body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        try {
            o.writeShort(nameIdx);
            o.writeInt(body.length);
            o.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    /** Adds a method with the given already-encoded attribute list (each element a full attribute). */
    Img methodWith(int acc, int nameIdx, int descIdx, byte[]... attributes) {
        return rawMember(methods, true, acc, nameIdx, descIdx, attributes);
    }

    Img field(int acc, String name, String desc, byte[]... attributes) {
        return rawMember(fields, false, acc, utf8(name), utf8(desc), attributes);
    }

    Img fieldIdx(int acc, int nameIdx, int descIdx) {
        return rawMember(fields, false, acc, nameIdx, descIdx);
    }

    private Img rawMember(ByteArrayOutputStream dst, boolean method, int acc, int n, int d, byte[]... attributes) {
        DataOutputStream o = new DataOutputStream(dst);
        try {
            o.writeShort(acc);
            o.writeShort(n);
            o.writeShort(d);
            o.writeShort(attributes.length);
            for (byte[] a : attributes) {
                o.write(a);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (method) {
            methodCount++;
        } else {
            fieldCount++;
        }
        return this;
    }

    Img classAttr(String name, byte[] body) {
        attrs.writeBytes(attr(utf8(name), body));
        attrCount++;
        return this;
    }

    byte[] bytes() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        try {
            o.writeInt(magic);
            o.writeShort(minor);
            o.writeShort(major);
            o.writeShort(poolCount);
            o.write(pool.toByteArray());
            o.writeShort(access);
            o.writeShort(thisIdx);
            o.writeShort(superIdx);
            o.writeShort(interfaces.length);
            for (int i : interfaces) {
                o.writeShort(i);
            }
            o.writeShort(fieldCount);
            o.write(fields.toByteArray());
            o.writeShort(methodCount);
            o.write(methods.toByteArray());
            o.writeShort(attrCount);
            o.write(attrs.toByteArray());
            o.write(trailing);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream o) throws IOException;
    }

    /** Modified UTF-8 via the JDK (writeUTF minus the two length bytes), independent of ModifiedUtf8. */
    static final class Modified {
        static byte[] encode(String s) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try {
                new DataOutputStream(b).writeUTF(s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            byte[] all = b.toByteArray();
            byte[] r = new byte[all.length - 2];
            System.arraycopy(all, 2, r, 0, r.length);
            return r;
        }
    }
}
