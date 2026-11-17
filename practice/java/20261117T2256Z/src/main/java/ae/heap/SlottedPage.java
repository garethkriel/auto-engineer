package ae.heap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * A fixed-size slotted page.
 *
 * <p>Layout: {@code u16 slotCount, u16 dataStart}, then the slot directory ({@code u16 offset,
 * u16 length} per slot; length {@code 0xFFFF} marks a deleted slot), free space, records growing
 * downwards from {@code pageSize - 4}, and a trailing CRC32 of all preceding bytes. Slot ids are
 * stable for the life of a record.
 */
public final class SlottedPage {
    private static final int HEADER = 4;
    private static final int SLOT = 4;
    private static final int CRC = 4;
    private static final int DELETED = 0xFFFF;

    private final byte[] buf;
    private final ByteBuffer bb;

    /** Creates an empty page of {@code pageSize} bytes (64..32768). */
    public SlottedPage(int pageSize) {
        this(checkSize(pageSize), true);
    }

    private SlottedPage(int pageSize, boolean fresh) {
        buf = new byte[pageSize];
        bb = ByteBuffer.wrap(buf);
        setDataStart(pageSize - CRC);
    }

    private SlottedPage(byte[] bytes) {
        buf = bytes;
        bb = ByteBuffer.wrap(buf);
    }

    private static int checkSize(int pageSize) {
        if (pageSize < 64 || pageSize > 32768) {
            throw new IllegalArgumentException("pageSize must be 64..32768: " + pageSize);
        }
        return pageSize;
    }

    /** Largest record a page of this size can ever hold. */
    public static int maxRecordSize(int pageSize) {
        return pageSize - CRC - HEADER - SLOT;
    }

    /** Number of slots ever allocated, live or deleted. */
    public int slotCount() { return bb.getShort(0) & 0xFFFF; }
    private void setSlotCount(int n) { bb.putShort(0, (short) n); }
    private int dataStart() { return bb.getShort(2) & 0xFFFF; }
    private void setDataStart(int v) { bb.putShort(2, (short) v); }
    private int offset(int slot) { return bb.getShort(HEADER + SLOT * slot) & 0xFFFF; }
    private int length(int slot) { return bb.getShort(HEADER + SLOT * slot + 2) & 0xFFFF; }
    private void setSlot(int slot, int off, int len) {
        bb.putShort(HEADER + SLOT * slot, (short) off);
        bb.putShort(HEADER + SLOT * slot + 2, (short) len);
    }
    private boolean live(int slot) { return length(slot) != DELETED; }
    private int end() { return buf.length - CRC; }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
    }

    private int usedData() {
        int sum = 0;
        for (int i = 0; i < slotCount(); i++) {
            if (live(i)) sum += length(i);
        }
        return sum;
    }

    /** Bytes reusable by records, counting holes left by deletes, minus the directory overhead. */
    private int totalFree() {
        return end() - HEADER - SLOT * slotCount() - usedData();
    }

    private int contiguousFree() {
        return dataStart() - (HEADER + SLOT * slotCount());
    }

    /** Largest record that could be inserted into a brand-new slot right now (after compaction). */
    public int freeSpace() {
        return Math.max(0, totalFree() - SLOT);
    }

    /** Number of live (non-deleted) records. */
    public int liveCount() {
        int n = 0;
        for (int i = 0; i < slotCount(); i++) {
            if (live(i)) n++;
        }
        return n;
    }

    /** Inserts a record; returns its slot id, or -1 if it does not fit. */
    public int insert(byte[] data) {
        int reuse = -1;
        for (int i = 0; i < slotCount(); i++) {
            if (!live(i)) { reuse = i; break; }
        }
        int need = data.length + (reuse < 0 ? SLOT : 0);
        if (need > totalFree()) return -1;
        if (need > contiguousFree()) compact();
        int slot = reuse;
        if (slot < 0) {
            slot = slotCount();
            setSlotCount(slot + 1);
        }
        place(slot, data);
        return slot;
    }

    private void place(int slot, byte[] data) {
        int off = dataStart() - data.length;
        System.arraycopy(data, 0, buf, off, data.length);
        setDataStart(off);
        setSlot(slot, off, data.length);
    }

    /** Returns the record in {@code slot}, or empty if it was deleted. */
    public Optional<byte[]> get(int slot) {
        checkSlot(slot);
        if (!live(slot)) return Optional.empty();
        int off = offset(slot);
        byte[] out = new byte[length(slot)];
        System.arraycopy(buf, off, out, 0, out.length);
        return Optional.of(out);
    }

    /** Deletes the record; returns false if it was already deleted. */
    public boolean delete(int slot) {
        checkSlot(slot);
        if (!live(slot)) return false;
        setSlot(slot, 0, DELETED);
        return true;
    }

    /**
     * Replaces the record in {@code slot}. Returns false (page unchanged) if the slot is deleted
     * or the new value cannot fit.
     */
    public boolean update(int slot, byte[] data) {
        checkSlot(slot);
        if (!live(slot)) return false;
        int oldLen = length(slot);
        if (data.length <= oldLen) {
            System.arraycopy(data, 0, buf, offset(slot), data.length);
            setSlot(slot, offset(slot), data.length);
            return true;
        }
        if (data.length > totalFree() + oldLen) return false;
        setSlot(slot, 0, DELETED);
        if (data.length > contiguousFree()) compact();
        place(slot, data);
        return true;
    }

    /** Moves all live records to the end of the page, removing holes. Slot ids are unchanged. */
    private void compact() {
        int n = slotCount();
        List<byte[]> copies = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            copies.add(live(i) ? get(i).orElseThrow() : null);
        }
        int pos = end();
        for (int i = 0; i < n; i++) {
            byte[] d = copies.get(i);
            if (d == null) continue;
            pos -= d.length;
            System.arraycopy(d, 0, buf, pos, d.length);
            setSlot(i, pos, d.length);
        }
        setDataStart(pos);
    }

    /** Serialises the page, with a fresh CRC32 in its last four bytes. */
    public byte[] toBytes() {
        byte[] out = buf.clone();
        CRC32 crc = new CRC32();
        crc.update(out, 0, out.length - CRC);
        ByteBuffer.wrap(out).putInt(out.length - CRC, (int) crc.getValue());
        return out;
    }

    /** Parses and validates a page image; the image length is the page size. */
    public static SlottedPage fromBytes(byte[] bytes) throws CorruptDataException {
        int size = bytes.length;
        if (size < 64 || size > 32768) throw new CorruptDataException("bad page length " + size);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, size - CRC);
        int stored = ByteBuffer.wrap(bytes).getInt(size - CRC);
        if (stored != (int) crc.getValue()) throw new CorruptDataException("page checksum mismatch");
        SlottedPage p = new SlottedPage(bytes.clone());
        p.validate();
        return p;
    }

    private void validate() throws CorruptDataException {
        int n = slotCount();
        int dirEnd = HEADER + SLOT * n;
        int ds = dataStart();
        if (dirEnd > ds || ds > end()) throw new CorruptDataException("bad slot directory");
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!live(i)) continue;
            int off = offset(i);
            int len = length(i);
            if (off < ds || off + len > end()) throw new CorruptDataException("slot " + i + " out of bounds");
            if (len > 0) spans.add(new int[] {off, off + len});
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i)[0] < spans.get(i - 1)[1]) throw new CorruptDataException("overlapping records");
        }
    }
}
