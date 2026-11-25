package ae.merkle;

import java.util.List;

/**
 * Inclusion or non-inclusion proof for one key: the full content of the key's bucket plus, for each level from the
 * root down, the 15 sibling hashes in ascending digit order (the path's own digit is skipped).
 */
public record Proof(List<Entry> bucket, List<List<byte[]>> siblings) {
    /** One key/value pair of a bucket. */
    public record Entry(String key, String value) {}
}
