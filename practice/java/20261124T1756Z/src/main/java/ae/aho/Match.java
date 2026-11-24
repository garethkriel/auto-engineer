package ae.aho;

/**
 * One occurrence of a pattern: the half-open range {@code [start, end)} of the text and the index
 * of the pattern in the list the automaton was built from.
 */
public record Match(int start, int end, int pattern) {
}
