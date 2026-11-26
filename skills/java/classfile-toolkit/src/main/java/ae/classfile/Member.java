package ae.classfile;

import java.util.List;

/** A field or method. */
public record Member(int access, int nameIndex, int descriptorIndex, String name, String descriptor,
                     List<Attribute> attributes) {
}
