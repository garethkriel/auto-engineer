package ae.prolog;

/** A 64-bit integer. All arithmetic on it is overflow-checked. */
public record Int(long value) implements Term {}
