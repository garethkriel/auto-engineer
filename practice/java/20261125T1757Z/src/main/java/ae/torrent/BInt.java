package ae.torrent;

/** Bencode integer (limited to the range of {@code long}). */
public record BInt(long value) implements BValue {
}
