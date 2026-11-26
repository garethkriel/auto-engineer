package ae.classfile;

/** The 1-based constant pool; the slot after a Long or Double is not addressable. */
public final class ConstantPool {
    private final Constant[] entries;

    /** @param entries array of length {@code constant_pool_count}; index 0 and Long/Double second slots are null */
    ConstantPool(Constant[] entries) {
        this.entries = entries;
    }

    /** The value of {@code constant_pool_count} (highest index + 1). */
    public int size() {
        return entries.length;
    }

    public Constant get(int index) {
        if (index <= 0 || index >= entries.length || entries[index] == null) {
            throw new ClassFormatException("constant pool index " + index + " out of range or invalid (count "
                + entries.length + ")");
        }
        return entries[index];
    }

    public String utf8(int index) {
        if (get(index) instanceof Constant.Utf8 u) {
            return u.value();
        }
        throw wrong(index, "Utf8");
    }

    public String className(int index) {
        if (get(index) instanceof Constant.ClassRef c) {
            return utf8(c.nameIndex());
        }
        throw wrong(index, "Class");
    }

    /** Resolves Fieldref, Methodref, InterfaceMethodref (owner set) and Dynamic/InvokeDynamic (owner null). */
    public MemberRef memberRef(int index) {
        Constant c = get(index);
        int cls;
        int nat;
        switch (c) {
            case Constant.FieldRef f -> {
                cls = f.classIndex();
                nat = f.natIndex();
            }
            case Constant.MethodRef m -> {
                cls = m.classIndex();
                nat = m.natIndex();
            }
            case Constant.InterfaceMethodRef m -> {
                cls = m.classIndex();
                nat = m.natIndex();
            }
            case Constant.Dynamic d -> {
                cls = 0;
                nat = d.natIndex();
            }
            case Constant.InvokeDynamic d -> {
                cls = 0;
                nat = d.natIndex();
            }
            default -> throw wrong(index, "a member reference");
        }
        if (!(get(nat) instanceof Constant.NameAndType nt)) {
            throw wrong(nat, "NameAndType");
        }
        return new MemberRef(cls == 0 ? null : className(cls), utf8(nt.nameIndex()), utf8(nt.descIndex()));
    }

    ClassFormatException wrong(int index, String expected) {
        String found = entries[index] == null ? "nothing" : entries[index].getClass().getSimpleName();
        return new ClassFormatException("wrong constant type at #" + index + ": expected " + expected + ", found " + found);
    }

    Constant[] raw() {
        return entries;
    }
}
