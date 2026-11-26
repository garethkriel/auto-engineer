package ae.classfile;

/** A constant-pool entry. Floating-point values keep their raw bits so NaN payloads round-trip. */
public sealed interface Constant {
    record Utf8(String value) implements Constant { }

    record IntegerC(int value) implements Constant { }

    record FloatC(int bits) implements Constant { }

    record LongC(long value) implements Constant { }

    record DoubleC(long bits) implements Constant { }

    record ClassRef(int nameIndex) implements Constant { }

    record StringRef(int utf8Index) implements Constant { }

    record FieldRef(int classIndex, int natIndex) implements Constant { }

    record MethodRef(int classIndex, int natIndex) implements Constant { }

    record InterfaceMethodRef(int classIndex, int natIndex) implements Constant { }

    record NameAndType(int nameIndex, int descIndex) implements Constant { }

    record MethodHandle(int kind, int refIndex) implements Constant { }

    record MethodType(int descIndex) implements Constant { }

    record Dynamic(int bootstrapIndex, int natIndex) implements Constant { }

    record InvokeDynamic(int bootstrapIndex, int natIndex) implements Constant { }

    record ModuleRef(int nameIndex) implements Constant { }

    record PackageRef(int nameIndex) implements Constant { }
}
