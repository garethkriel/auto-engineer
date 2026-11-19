package ae.coord;

/** Metadata of a node. {@code ephemeralOwner} is 0 for persistent nodes. */
public record Stat(long czxid, long mzxid, int version, int cversion, long ephemeralOwner, int numChildren) {}
