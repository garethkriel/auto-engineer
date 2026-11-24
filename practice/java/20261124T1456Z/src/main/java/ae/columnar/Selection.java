package ae.columnar;

/**
 * Result of {@link Table#select}: matching row ids in ascending order plus how each segment was handled.
 * The three counters always sum to the table's segment count.
 */
public record Selection(int[] rows, int segmentsPruned, int segmentsAllMatch, int segmentsScanned) {
}
