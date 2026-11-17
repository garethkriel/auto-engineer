package ae.tsblock;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Writes a time-series file. Output goes to a temp file next to the target and is atomically
 * moved onto the target by {@link #close()}; {@link #abort()} discards it. Not thread-safe.
 */
public final class TsWriter implements AutoCloseable {
    private final Path target;
    private final Path temp;
    private final FileOutputStream fos;
    private final BufferedOutputStream out;
    private final int blockSize;
    private final List<Point> pending = new ArrayList<>();
    private final ByteArrayOutputStream index = new ByteArrayOutputStream();
    private int blockCount;
    private long position;
    private boolean hasLast;
    private long lastTs;
    private boolean finished;

    private TsWriter(Path target, Path temp, FileOutputStream fos, int blockSize) throws IOException {
        this.target = target;
        this.temp = temp;
        this.fos = fos;
        this.out = new BufferedOutputStream(fos);
        this.blockSize = blockSize;
        out.write(Format.HEAD_MAGIC);
        position = Format.HEAD_MAGIC.length;
    }

    public static TsWriter create(Path target, int blockSize) throws IOException {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be >= 1");
        }
        Path abs = target.toAbsolutePath();
        Path temp = Files.createTempFile(abs.getParent(), ".tsb-", ".tmp");
        try {
            return new TsWriter(abs, temp, new FileOutputStream(temp.toFile()), blockSize);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Appends a point; timestamps must be strictly increasing. */
    public void append(long ts, long value) throws IOException {
        if (finished) {
            throw new IllegalStateException("writer is closed");
        }
        if (hasLast && ts <= lastTs) {
            throw new IllegalArgumentException("timestamp " + ts + " not after " + lastTs);
        }
        pending.add(new Point(ts, value));
        hasLast = true;
        lastTs = ts;
        if (pending.size() >= blockSize) {
            flushBlock();
        }
    }

    private void flushBlock() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        byte[] payload = BlockCodec.encode(pending);
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer header = ByteBuffer.allocate(Format.BLOCK_HEADER);
        header.putInt(payload.length).putInt((int) crc.getValue());
        out.write(header.array());
        out.write(payload);

        ByteBuffer entry = ByteBuffer.allocate(Format.INDEX_ENTRY);
        entry.putLong(position)
                .putLong(pending.get(0).ts())
                .putLong(pending.get(pending.size() - 1).ts())
                .putInt(pending.size());
        index.write(entry.array());
        blockCount++;
        position += Format.BLOCK_HEADER + payload.length;
        pending.clear();
    }

    /** Finishes the file and moves it onto the target. Idempotent. */
    @Override
    public void close() throws IOException {
        if (finished) {
            return;
        }
        try {
            flushBlock();
            byte[] entries = index.toByteArray();
            ByteBuffer head = ByteBuffer.allocate(4).putInt(blockCount);
            CRC32 crc = new CRC32();
            crc.update(head.array());
            crc.update(entries);
            long indexOffset = position;
            out.write(head.array());
            out.write(entries);
            out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
            out.write(ByteBuffer.allocate(8).putLong(indexOffset).array());
            out.write(Format.TAIL_MAGIC);
            out.flush();
            fos.getFD().sync();
            out.close();
            finished = true;
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            abort();
            throw e;
        }
    }

    /** Discards everything written so far; the target is left untouched. */
    public void abort() throws IOException {
        if (!finished || Files.exists(temp)) {
            finished = true;
            try {
                out.close();
            } catch (IOException ignored) {
                // closing a broken stream; the temp file is deleted next
            }
            Files.deleteIfExists(temp);
        }
        finished = true;
    }
}
