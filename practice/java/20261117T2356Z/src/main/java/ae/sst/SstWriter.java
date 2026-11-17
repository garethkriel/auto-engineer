package ae.sst;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Writes an immutable SSTable. See the package TASK for the layout. */
public final class SstWriter {
    static final byte[] MAGIC = {'S', 'S', 'T', '1'};
    static final int INDEX_INTERVAL = 4;
    static final int FOOTER_SIZE = 28;

    private SstWriter() {}

    /** Writes {@code sorted} (strictly ascending keys) to {@code file}; returns the number of entries. */
    public static int write(Path file, Iterable<Entry> sorted) throws IOException {
        // Build the whole image in memory first: any validation failure happens before touching the disk.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.write(MAGIC);
        List<byte[]> indexKeys = new ArrayList<>();
        List<Long> indexOffsets = new ArrayList<>();
        List<byte[]> allKeys = new ArrayList<>();
        byte[] prev = null;
        for (Entry e : sorted) {
            byte[] k = e.key().getBytes(StandardCharsets.UTF_8);
            if (prev != null && java.util.Arrays.compareUnsigned(prev, k) >= 0) {
                throw new IllegalArgumentException("keys must be strictly ascending at '" + e.key() + "'");
            }
            if (allKeys.size() % INDEX_INTERVAL == 0) {
                indexKeys.add(k);
                indexOffsets.add((long) out.size());
            }
            byte[] v = e.value();
            out.writeShort(k.length);
            out.writeInt(v == null ? -1 : v.length);
            out.write(k);
            if (v != null) out.write(v);
            allKeys.add(k);
            prev = k;
        }
        long indexOff = out.size();
        for (int i = 0; i < indexKeys.size(); i++) {
            out.writeShort(indexKeys.get(i).length);
            out.write(indexKeys.get(i));
            out.writeLong(indexOffsets.get(i));
        }
        long bloomOff = out.size();
        BloomFilter bloom = BloomFilter.forCount(allKeys.size());
        for (byte[] k : allKeys) bloom.add(k);
        out.write(bloom.toBytes());
        out.writeLong(indexOff);
        out.writeLong(bloomOff);
        out.writeInt(allKeys.size());
        CRC32 crc = new CRC32();
        crc.update(buf.toByteArray());
        out.writeInt((int) crc.getValue());
        out.write(MAGIC);

        Path parent = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".sst-", ".tmp");
        try {
            Files.write(tmp, buf.toByteArray());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return allKeys.size();
    }
}
