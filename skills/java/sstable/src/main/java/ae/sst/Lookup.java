package ae.sst;

/** Result of {@link SstReader#get}: FOUND carries a value, DELETED means a tombstone, ABSENT means no record. */
public record Lookup(Kind kind, byte[] value) {
    public enum Kind { FOUND, DELETED, ABSENT }

    static final Lookup ABSENT = new Lookup(Kind.ABSENT, null);
    static final Lookup DELETED = new Lookup(Kind.DELETED, null);
}
