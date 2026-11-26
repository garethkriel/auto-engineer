package ae.classfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict class-file parser (JVMS chapter 4); every defect becomes a {@link ClassFormatException}. */
public final class ClassReader {
    private static final int MIN_MAJOR = 45;
    private static final int MAX_MAJOR = 65;

    private final byte[] data;
    private int pos;

    private ClassReader(byte[] data) {
        this.data = data;
    }

    public static ClassFile parse(byte[] image) {
        return new ClassReader(image).read();
    }

    /** Decodes and validates a Code attribute body. */
    static CodeAttr parseCode(ConstantPool pool, byte[] body) {
        ClassReader r = new ClassReader(body);
        int maxStack = r.u2();
        int maxLocals = r.u2();
        long codeLength = r.u4();
        if (codeLength < 1 || codeLength > 65535) {
            throw new ClassFormatException("bad code length " + codeLength);
        }
        byte[] code = r.bytes((int) codeLength);
        int n = r.u2();
        List<ExceptionEntry> handlers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int start = r.u2();
            int end = r.u2();
            int handler = r.u2();
            int type = r.u2();
            if (start >= end || end > code.length || handler >= code.length) {
                throw new ClassFormatException("bad exception table row " + i + ": " + start + ".." + end + " -> " + handler);
            }
            if (type != 0) {
                pool.className(type);
            }
            handlers.add(new ExceptionEntry(start, end, handler, type));
        }
        List<Attribute> attrs = r.attributes(pool);
        if (r.pos != body.length) {
            throw new ClassFormatException("Code attribute length does not match its content");
        }
        return new CodeAttr(maxStack, maxLocals, code, List.copyOf(handlers), List.copyOf(attrs));
    }

    private ClassFile read() {
        if (u4() != 0xCAFEBABEL) {
            throw new ClassFormatException("bad magic number");
        }
        int minor = u2();
        int major = u2();
        if (major < MIN_MAJOR || major > MAX_MAJOR) {
            throw new ClassFormatException("unsupported version " + major + "." + minor);
        }
        ConstantPool pool = readPool();
        int access = u2();
        int thisIndex = u2();
        pool.className(thisIndex);
        int superIndex = u2();
        if (superIndex == 0) {
            if (!pool.className(thisIndex).equals("java/lang/Object") && (access & ClassFile.ACC_MODULE) == 0) {
                throw new ClassFormatException("missing superclass for " + pool.className(thisIndex));
            }
        } else {
            pool.className(superIndex);
        }
        int ifaceCount = u2();
        List<Integer> ifaces = new ArrayList<>();
        for (int i = 0; i < ifaceCount; i++) {
            int idx = u2();
            pool.className(idx);
            ifaces.add(idx);
        }
        List<Member> fields = members(pool, false);
        List<Member> methods = members(pool, true);
        List<Attribute> attrs = attributes(pool);
        if (pos != data.length) {
            throw new ClassFormatException("trailing bytes after class file (" + (data.length - pos) + ")");
        }
        return new ClassFile(minor, major, pool, access, thisIndex, superIndex, List.copyOf(ifaces),
            List.copyOf(fields), List.copyOf(methods), List.copyOf(attrs));
    }

    private ConstantPool readPool() {
        int count = u2();
        if (count == 0) {
            throw new ClassFormatException("constant pool count is zero");
        }
        Constant[] e = new Constant[count];
        for (int i = 1; i < count; i++) {
            int tag = u1();
            switch (tag) {
                case 1 -> {
                    int len = u2();
                    e[i] = new Constant.Utf8(ModifiedUtf8.decode(data, need(len), len));
                    pos += len;
                }
                case 3 -> e[i] = new Constant.IntegerC((int) u4());
                case 4 -> e[i] = new Constant.FloatC((int) u4());
                case 5, 6 -> {
                    if (i + 1 >= count) {
                        throw new ClassFormatException("long/double constant at #" + i + " overruns the pool");
                    }
                    long v = (u4() << 32) | u4();
                    e[i] = tag == 5 ? new Constant.LongC(v) : new Constant.DoubleC(v);
                    i++;
                }
                case 7 -> e[i] = new Constant.ClassRef(u2());
                case 8 -> e[i] = new Constant.StringRef(u2());
                case 9 -> e[i] = new Constant.FieldRef(u2(), u2());
                case 10 -> e[i] = new Constant.MethodRef(u2(), u2());
                case 11 -> e[i] = new Constant.InterfaceMethodRef(u2(), u2());
                case 12 -> e[i] = new Constant.NameAndType(u2(), u2());
                case 15 -> e[i] = new Constant.MethodHandle(u1(), u2());
                case 16 -> e[i] = new Constant.MethodType(u2());
                case 17 -> e[i] = new Constant.Dynamic(u2(), u2());
                case 18 -> e[i] = new Constant.InvokeDynamic(u2(), u2());
                case 19 -> e[i] = new Constant.ModuleRef(u2());
                case 20 -> e[i] = new Constant.PackageRef(u2());
                default -> throw new ClassFormatException("unknown constant tag " + tag + " at #" + i);
            }
        }
        ConstantPool pool = new ConstantPool(e);
        for (int i = 1; i < count; i++) {
            if (e[i] != null) {
                validate(pool, e[i]);
            }
        }
        return pool;
    }

    private static void validate(ConstantPool pool, Constant c) {
        switch (c) {
            case Constant.ClassRef r -> pool.utf8(r.nameIndex());
            case Constant.StringRef r -> pool.utf8(r.utf8Index());
            case Constant.MethodType r -> pool.utf8(r.descIndex());
            case Constant.ModuleRef r -> pool.utf8(r.nameIndex());
            case Constant.PackageRef r -> pool.utf8(r.nameIndex());
            case Constant.NameAndType r -> {
                pool.utf8(r.nameIndex());
                pool.utf8(r.descIndex());
            }
            case Constant.FieldRef r -> owner(pool, r.classIndex(), r.natIndex());
            case Constant.MethodRef r -> owner(pool, r.classIndex(), r.natIndex());
            case Constant.InterfaceMethodRef r -> owner(pool, r.classIndex(), r.natIndex());
            case Constant.Dynamic r -> nameAndType(pool, r.natIndex());
            case Constant.InvokeDynamic r -> nameAndType(pool, r.natIndex());
            case Constant.MethodHandle h -> {
                if (h.kind() < 1 || h.kind() > 9) {
                    throw new ClassFormatException("bad method handle kind " + h.kind());
                }
                Constant target = pool.get(h.refIndex());
                boolean ok = switch (h.kind()) {
                    case 1, 2, 3, 4 -> target instanceof Constant.FieldRef;
                    case 9 -> target instanceof Constant.InterfaceMethodRef;
                    default -> target instanceof Constant.MethodRef || target instanceof Constant.InterfaceMethodRef;
                };
                if (!ok) {
                    throw pool.wrong(h.refIndex(), "a reference matching handle kind " + h.kind());
                }
            }
            case Constant.Utf8 u -> { }
            case Constant.IntegerC u -> { }
            case Constant.FloatC u -> { }
            case Constant.LongC u -> { }
            case Constant.DoubleC u -> { }
        }
    }

    private static void owner(ConstantPool pool, int classIndex, int natIndex) {
        pool.className(classIndex);
        nameAndType(pool, natIndex);
    }

    private static void nameAndType(ConstantPool pool, int natIndex) {
        if (!(pool.get(natIndex) instanceof Constant.NameAndType)) {
            throw pool.wrong(natIndex, "NameAndType");
        }
    }

    private List<Member> members(ConstantPool pool, boolean methods) {
        int n = u2();
        List<Member> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int access = u2();
            int nameIdx = u2();
            int descIdx = u2();
            String name = pool.utf8(nameIdx);
            String desc = pool.utf8(descIdx);
            if (methods) {
                checkMethodName(name);
                Descriptor.parseMethod(desc);
            } else {
                checkUnqualified(name);
                Descriptor.parseField(desc);
            }
            if (!seen.add(name + ":" + desc)) {
                throw new ClassFormatException("duplicate member " + name + desc);
            }
            List<Attribute> attrs = attributes(pool);
            if (methods) {
                checkCodePresence(pool, access, name, attrs);
            }
            out.add(new Member(access, nameIdx, descIdx, name, desc, List.copyOf(attrs)));
        }
        return out;
    }

    private static void checkCodePresence(ConstantPool pool, int access, String name, List<Attribute> attrs) {
        int codes = 0;
        Attribute code = null;
        for (Attribute a : attrs) {
            if (a.name().equals("Code")) {
                codes++;
                code = a;
            }
        }
        boolean bodyless = (access & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_NATIVE)) != 0;
        if (bodyless && codes > 0) {
            throw new ClassFormatException("method " + name + " must not have Code");
        }
        if (!bodyless && codes == 0) {
            throw new ClassFormatException("method " + name + " is missing Code");
        }
        if (codes > 1) {
            throw new ClassFormatException("method " + name + " has multiple Code attributes");
        }
        if (code != null) {
            parseCode(pool, code.data());
        }
    }

    private static void checkUnqualified(String name) {
        if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0
            || name.indexOf('/') >= 0) {
            throw new ClassFormatException("bad name '" + name + "'");
        }
    }

    private static void checkMethodName(String name) {
        checkUnqualified(name);
        boolean special = name.equals("<init>") || name.equals("<clinit>");
        if (!special && (name.indexOf('<') >= 0 || name.indexOf('>') >= 0)) {
            throw new ClassFormatException("bad name '" + name + "'");
        }
    }

    private List<Attribute> attributes(ConstantPool pool) {
        int n = u2();
        List<Attribute> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int nameIdx = u2();
            String name = pool.utf8(nameIdx);
            long len = u4();
            if (len > data.length - pos) {
                throw new ClassFormatException("truncated attribute " + name);
            }
            out.add(new Attribute(nameIdx, name, bytes((int) len)));
        }
        return out;
    }

    // ---- cursor ------------------------------------------------------------

    /** Checks that {@code n} more bytes exist and returns the current position. */
    private int need(int n) {
        if (n < 0 || n > data.length - pos) {
            throw new ClassFormatException("truncated class file at offset " + pos);
        }
        return pos;
    }

    private int u1() {
        need(1);
        return data[pos++] & 0xFF;
    }

    private int u2() {
        need(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    private long u4() {
        need(4);
        long v = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
            | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    private byte[] bytes(int n) {
        need(n);
        byte[] r = java.util.Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return r;
    }
}
