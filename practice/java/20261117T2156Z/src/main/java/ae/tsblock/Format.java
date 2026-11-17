package ae.tsblock;

import java.nio.charset.StandardCharsets;

/** Constants of the on-disk layout (all integers big-endian). */
final class Format {
    private Format() {}

    static final byte[] HEAD_MAGIC = "TSB1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] TAIL_MAGIC = "TSBE".getBytes(StandardCharsets.US_ASCII);
    /** Block frame header: u32 payload length + u32 CRC32 of payload. */
    static final int BLOCK_HEADER = 8;
    /** Index entry: u64 offset, i64 minTs, i64 maxTs, u32 count. */
    static final int INDEX_ENTRY = 28;
    /** Trailer: u64 index offset + tail magic. */
    static final int TRAILER = 12;
}
