package ae.rsync;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/** Per-block weak and strong checksums of a basis file; what the receiver sends to the sender. */
public record Signature(int blockSize, long fileLength, List<BlockSig> blocks) {
    private static final byte[] MAGIC = {'R', 'S', 'S', '1'};
    private static final int STRONG = 32;
    private static final int HEADER = 4 + 4 + 8 + 4;
    private static final int BLOCK_WIRE = 4 + STRONG;
    private static final int CRC = 4;

    /** Weak and strong checksum of one block. */
    public record BlockSig(int weak, byte[] strong) {
        public BlockSig {
            Objects.requireNonNull(strong, "strong");
            if (strong.length != STRONG) {
                throw new IllegalArgumentException("strong digest must be 32 bytes");
            }
            strong = strong.clone();
        }

        @Override
        public byte[] strong() {
            return strong.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BlockSig s && s.weak == weak && Arrays.equals(s.strong, strong);
        }

        @Override
        public int hashCode() {
            return 31 * weak + Arrays.hashCode(strong);
        }
    }

    public Signature {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (fileLength < 0) {
            throw new IllegalArgumentException("fileLength must not be negative");
        }
        blocks = List.copyOf(blocks);
        long expected = blockCountFor(fileLength, blockSize);
        if (blocks.size() != expected) {
            throw new IllegalArgumentException("block count " + blocks.size() + " != expected " + expected);
        }
    }

    private static long blockCountFor(long fileLength, int blockSize) {
        return (fileLength + blockSize - 1) / blockSize;
    }

    public static Signature of(byte[] basis, int blockSize) {
        Objects.requireNonNull(basis, "basis");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        MessageDigest md = DigestSupport.sha256();
        List<BlockSig> out = new ArrayList<>();
        for (int pos = 0; pos < basis.length; pos += blockSize) {
            int len = Math.min(blockSize, basis.length - pos);
            md.update(basis, pos, len);
            out.add(new BlockSig(Rolling.weak(basis, pos, len), md.digest()));
        }
        return new Signature(blockSize, basis.length, out);
    }

    /** Length in bytes of block {@code index} (the last block may be shorter). */
    public int lengthOfBlock(int index) {
        Objects.checkIndex(index, blocks.size());
        long start = (long) index * blockSize;
        return (int) Math.min(blockSize, fileLength - start);
    }

    /** Length of the last block, or 0 when the basis is empty. */
    public int lastBlockLength() {
        return blocks.isEmpty() ? 0 : lengthOfBlock(blocks.size() - 1);
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER + blocks.size() * BLOCK_WIRE + CRC);
        buf.put(MAGIC).putInt(blockSize).putLong(fileLength).putInt(blocks.size());
        for (BlockSig b : blocks) {
            buf.putInt(b.weak).put(b.strong);
        }
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    public static Signature parse(byte[] wire) throws FormatException {
        Objects.requireNonNull(wire, "wire");
        if (wire.length < HEADER + CRC) {
            throw new FormatException("signature truncated");
        }
        CRC32 crc = new CRC32();
        crc.update(wire, 0, wire.length - CRC);
        int stored = ByteBuffer.wrap(wire, wire.length - CRC, CRC).getInt();
        if ((int) crc.getValue() != stored) {
            throw new FormatException("signature checksum mismatch");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire, 0, wire.length - CRC);
        byte[] magic = new byte[4];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new FormatException("bad signature magic");
        }
        int blockSize = buf.getInt();
        long fileLength = buf.getLong();
        long count = buf.getInt() & 0xFFFFFFFFL;
        if (blockSize <= 0 || fileLength < 0) {
            throw new FormatException("bad signature header");
        }
        if (count != blockCountFor(fileLength, blockSize)) {
            throw new FormatException("block count " + count + " does not match file length " + fileLength);
        }
        if ((long) buf.remaining() != count * BLOCK_WIRE) {
            throw new FormatException("signature size does not match block count");
        }
        List<BlockSig> blocks = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            int weak = buf.getInt();
            byte[] strong = new byte[STRONG];
            buf.get(strong);
            blocks.add(new BlockSig(weak, strong));
        }
        return new Signature(blockSize, fileLength, blocks);
    }
}
