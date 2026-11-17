package ae.heap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * A heap of records stored in {@link SlottedPage}s and persisted as one file: a 16-byte header
 * (magic, pageSize, pageCount, CRC32 of those three ints) followed by the pages. Changes are held
 * in memory until {@link #flush()}, which replaces the file atomically.
 */
public final class HeapFile {
    private static final int MAGIC = 0x48454150; // "HEAP"
    private static final int HEADER = 16;

    /** Record id: page number and slot within the page. */
    public record Rid(int page, int slot) { }

    /** A scanned record. */
    public record Entry(Rid rid, byte[] data) { }

    private final Path path;
    private final int pageSize;
    private final List<SlottedPage> pages;

    private HeapFile(Path path, int pageSize, List<SlottedPage> pages) {
        this.path = path;
        this.pageSize = pageSize;
        this.pages = pages;
    }

    /** Opens the heap at {@code path}; a missing or empty (zero-byte) file is an empty heap. */
    public static HeapFile open(Path path, int pageSize) throws IOException {
        new SlottedPage(pageSize); // validates the size
        List<SlottedPage> pages = new ArrayList<>();
        if (Files.exists(path)) {
            byte[] all = Files.readAllBytes(path);
            if (all.length > 0) {
                if (all.length < HEADER) throw new CorruptDataException("truncated header");
                ByteBuffer bb = ByteBuffer.wrap(all);
                CRC32 crc = new CRC32();
                crc.update(all, 0, 12);
                if (bb.getInt(12) != (int) crc.getValue()) throw new CorruptDataException("header checksum mismatch");
                if (bb.getInt(0) != MAGIC) throw new CorruptDataException("bad magic");
                if (bb.getInt(4) != pageSize) throw new CorruptDataException("page size mismatch: file has " + bb.getInt(4));
                int count = bb.getInt(8);
                if (count < 0 || (long) HEADER + (long) count * pageSize != all.length) {
                    throw new CorruptDataException("file length does not match page count");
                }
                for (int i = 0; i < count; i++) {
                    byte[] page = new byte[pageSize];
                    System.arraycopy(all, HEADER + i * pageSize, page, 0, pageSize);
                    pages.add(SlottedPage.fromBytes(page));
                }
            }
        }
        return new HeapFile(path, pageSize, pages);
    }

    public int pageCount() {
        return pages.size();
    }

    /** Stores a record in the first page with room (appending a page if needed). */
    public Rid insert(byte[] data) {
        if (data.length > SlottedPage.maxRecordSize(pageSize)) {
            throw new IllegalArgumentException("record too large: " + data.length);
        }
        for (int p = 0; p < pages.size(); p++) {
            int slot = pages.get(p).insert(data);
            if (slot >= 0) return new Rid(p, slot);
        }
        SlottedPage fresh = new SlottedPage(pageSize);
        int slot = fresh.insert(data);
        pages.add(fresh);
        return new Rid(pages.size() - 1, slot);
    }

    private boolean valid(Rid rid) {
        return rid.page() >= 0 && rid.page() < pages.size() && rid.slot() >= 0
                && rid.slot() < slotCountOf(rid.page());
    }

    private int slotCountOf(int page) {
        return pages.get(page).slotCount();
    }

    public Optional<byte[]> get(Rid rid) {
        return valid(rid) ? pages.get(rid.page()).get(rid.slot()) : Optional.empty();
    }

    public boolean delete(Rid rid) {
        return valid(rid) && pages.get(rid.page()).delete(rid.slot());
    }

    /** Updates in place; returns false if the record is gone or the page has no room. */
    public boolean update(Rid rid, byte[] data) {
        return valid(rid) && pages.get(rid.page()).update(rid.slot(), data);
    }

    /** All live records in (page, slot) order. */
    public List<Entry> scan() {
        List<Entry> out = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            SlottedPage page = pages.get(p);
            for (int s = 0; s < page.slotCount(); s++) {
                Optional<byte[]> d = page.get(s);
                if (d.isPresent()) out.add(new Entry(new Rid(p, s), d.get()));
            }
        }
        return out;
    }

    /** Writes the whole heap to a temp file in the same directory, then atomically moves it over. */
    public void flush() throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(HEADER + pages.size() * pageSize);
        bb.putInt(MAGIC).putInt(pageSize).putInt(pages.size());
        CRC32 crc = new CRC32();
        crc.update(bb.array(), 0, 12);
        bb.putInt((int) crc.getValue());
        for (SlottedPage p : pages) bb.put(p.toBytes());
        Path dir = path.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bb.array());
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
