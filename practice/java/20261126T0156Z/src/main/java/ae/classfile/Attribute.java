package ae.classfile;

/** An attribute kept as raw bytes (the Code attribute is decoded on demand by {@link ClassFile#code}). */
public record Attribute(int nameIndex, String name, byte[] data) {
}
