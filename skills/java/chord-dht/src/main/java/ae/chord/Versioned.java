package ae.chord;

/** A stored value with the network-clock version of the write that produced it. */
public record Versioned(long version, String value) {
}
