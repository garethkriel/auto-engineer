package ae.classfile;

/** One row of a Code attribute's exception table; {@code catchType} 0 means any exception. */
public record ExceptionEntry(int start, int end, int handler, int catchType) {
}
