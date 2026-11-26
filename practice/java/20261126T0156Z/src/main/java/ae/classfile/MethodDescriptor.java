package ae.classfile;

import java.util.List;

/** A parsed method descriptor. */
public record MethodDescriptor(List<Type> params, Type ret) {
    public int argSlots() {
        int n = 0;
        for (Type t : params) {
            n += t.slots();
        }
        return n;
    }
}
