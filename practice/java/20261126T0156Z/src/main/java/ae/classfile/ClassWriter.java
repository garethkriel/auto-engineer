package ae.classfile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Serialises a {@link ClassFile}; the exact inverse of {@link ClassReader#parse}. */
public final class ClassWriter {
    private ClassWriter() {
    }

    public static byte[] toBytes(ClassFile cf) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bos);
        try {
            o.writeInt(0xCAFEBABE);
            o.writeShort(cf.minor());
            o.writeShort(cf.major());
            Constant[] pool = cf.pool().raw();
            o.writeShort(pool.length);
            for (Constant c : pool) {
                if (c != null) {
                    writeConstant(o, c);
                }
            }
            o.writeShort(cf.access());
            o.writeShort(cf.thisIndex());
            o.writeShort(cf.superIndex());
            o.writeShort(cf.interfaceIndexes().size());
            for (int i : cf.interfaceIndexes()) {
                o.writeShort(i);
            }
            members(o, cf.fields());
            members(o, cf.methods());
            attributes(o, cf.attributes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static void members(DataOutputStream o, java.util.List<Member> ms) throws IOException {
        o.writeShort(ms.size());
        for (Member m : ms) {
            o.writeShort(m.access());
            o.writeShort(m.nameIndex());
            o.writeShort(m.descriptorIndex());
            attributes(o, m.attributes());
        }
    }

    private static void attributes(DataOutputStream o, java.util.List<Attribute> as) throws IOException {
        o.writeShort(as.size());
        for (Attribute a : as) {
            o.writeShort(a.nameIndex());
            o.writeInt(a.data().length);
            o.write(a.data());
        }
    }

    private static void writeConstant(DataOutputStream o, Constant c) throws IOException {
        switch (c) {
            case Constant.Utf8 u -> {
                byte[] b = ModifiedUtf8.encode(u.value());
                o.writeByte(1);
                o.writeShort(b.length);
                o.write(b);
            }
            case Constant.IntegerC v -> {
                o.writeByte(3);
                o.writeInt(v.value());
            }
            case Constant.FloatC v -> {
                o.writeByte(4);
                o.writeInt(v.bits());
            }
            case Constant.LongC v -> {
                o.writeByte(5);
                o.writeLong(v.value());
            }
            case Constant.DoubleC v -> {
                o.writeByte(6);
                o.writeLong(v.bits());
            }
            case Constant.ClassRef v -> two(o, 7, v.nameIndex());
            case Constant.StringRef v -> two(o, 8, v.utf8Index());
            case Constant.FieldRef v -> three(o, 9, v.classIndex(), v.natIndex());
            case Constant.MethodRef v -> three(o, 10, v.classIndex(), v.natIndex());
            case Constant.InterfaceMethodRef v -> three(o, 11, v.classIndex(), v.natIndex());
            case Constant.NameAndType v -> three(o, 12, v.nameIndex(), v.descIndex());
            case Constant.MethodHandle v -> {
                o.writeByte(15);
                o.writeByte(v.kind());
                o.writeShort(v.refIndex());
            }
            case Constant.MethodType v -> two(o, 16, v.descIndex());
            case Constant.Dynamic v -> three(o, 17, v.bootstrapIndex(), v.natIndex());
            case Constant.InvokeDynamic v -> three(o, 18, v.bootstrapIndex(), v.natIndex());
            case Constant.ModuleRef v -> two(o, 19, v.nameIndex());
            case Constant.PackageRef v -> two(o, 20, v.nameIndex());
        }
    }

    private static void two(DataOutputStream o, int tag, int a) throws IOException {
        o.writeByte(tag);
        o.writeShort(a);
    }

    private static void three(DataOutputStream o, int tag, int a, int b) throws IOException {
        o.writeByte(tag);
        o.writeShort(a);
        o.writeShort(b);
    }
}
