package ae.classfile;

import java.util.List;

/** A decoded Code attribute. */
public record CodeAttr(int maxStack, int maxLocals, byte[] code, List<ExceptionEntry> handlers,
                       List<Attribute> attributes) {
}
