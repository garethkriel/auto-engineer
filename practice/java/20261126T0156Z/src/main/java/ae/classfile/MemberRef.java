package ae.classfile;

/** A resolved field, method or dynamic reference; {@code owner} is null for invokedynamic/condy. */
public record MemberRef(String owner, String name, String descriptor) {
}
