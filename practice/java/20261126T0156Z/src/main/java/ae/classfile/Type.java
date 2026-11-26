package ae.classfile;

/**
 * A field or return type. {@code kind} is the descriptor letter of the element type
 * ({@code 'L'} for classes, {@code 'V'} for void); {@code dims} counts array dimensions.
 */
public record Type(char kind, String className, int dims) {
    /** Operand-stack / local-variable slots this type occupies. */
    public int slots() {
        if (dims > 0) {
            return 1;
        }
        return switch (kind) {
            case 'V' -> 0;
            case 'J', 'D' -> 2;
            default -> 1;
        };
    }

    public boolean isVoid() {
        return kind == 'V' && dims == 0;
    }
}
