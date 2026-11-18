package ae.vcs;

import java.util.Optional;

/** Port: immutable raw objects addressed by the hash of their bytes. */
public interface ObjectStore {
    /** Stores raw bytes (idempotent) and returns their id. */
    ObjectId put(byte[] raw);

    /** Returns the raw bytes, verifying the hash; throws {@link CorruptObjectException} on mismatch. */
    Optional<byte[]> get(ObjectId id);
}
