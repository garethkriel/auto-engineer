package ae.classfile;

import java.util.List;
import java.util.Optional;

/** A parsed class file. All indexes refer to {@link #pool()}. */
public record ClassFile(int minor, int major, ConstantPool pool, int access, int thisIndex, int superIndex,
                        List<Integer> interfaceIndexes, List<Member> fields, List<Member> methods,
                        List<Attribute> attributes) {
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_MODULE = 0x8000;

    public String name() {
        return pool.className(thisIndex);
    }

    /** The superclass name, or null for java/lang/Object and module descriptors. */
    public String superName() {
        return superIndex == 0 ? null : pool.className(superIndex);
    }

    public List<String> interfaceNames() {
        return interfaceIndexes.stream().map(pool::className).toList();
    }

    /** The decoded Code attribute of a method, if it has one. */
    public Optional<CodeAttr> code(Member m) {
        for (Attribute a : m.attributes()) {
            if (a.name().equals("Code")) {
                return Optional.of(ClassReader.parseCode(pool, a.data()));
            }
        }
        return Optional.empty();
    }
}
