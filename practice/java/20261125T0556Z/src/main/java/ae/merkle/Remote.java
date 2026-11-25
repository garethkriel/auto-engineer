package ae.merkle;

import java.util.List;

/** Read-only view of another replica's tree. Implementations may be hostile; callers verify every reply. */
public interface Remote {
    /** Hash of the node at {@code path} (0..4 hex nibbles). */
    byte[] hashAt(String path);

    /** The 16 child hashes of the inner node at {@code path} (0..3 nibbles). */
    byte[][] childHashes(String path);

    /** Entries of the bucket at {@code path} (exactly 4 nibbles), ascending by key. */
    List<Proof.Entry> bucket(String path);
}
