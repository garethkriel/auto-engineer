package ae.sst;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Read-only view of an SSTable file. The whole file is validated on {@link #open}. */
public final class SstReader {
    private final byte[] data;
    private final int dataEnd;
    private final int count;
    private final byte[][] indexKeys;
    private final int[] indexOffsets;
    private final BloomFilter bloom;

    private SstReader(byte[] data, int dataEnd, int count, byte[][] indexKeys, int[] indexOffsets, BloomFilter bloom) {
        this.data = data;
        this.dataEnd = dataEnd;
        this.count = count;
        this.indexKeys = indexKeys;
        this.indexOffsets = indexOffsets;
        this.bloom = bloom;
    }

    public static SstReader open(Path file) throws IOException {
        byte[] d = Files.readAllBytes(file);
        int n = d.length;
        int footer = n - SstWriter.FOOTER_SIZE;
        if (footer < 4) throw new CorruptSstException("file too short: " + n);
        if (!Arrays.equals(d, 0, 4, SstWriter.MAGIC, 0, 4) || !Arrays.equals(d, n - 4, n, SstWriter.MAGIC, 0, 4)) {
            throw new CorruptSstException("bad magic");
        }
        ByteBuffer bb = ByteBuffer.wrap(d);
        long indexOffL = bb.getLong(footer);
        long bloomOffL = bb.getLong(footer + 8);
        int count = bb.getInt(footer + 16);
        int stored = bb.getInt(footer + 20);
        CRC32 crc = new CRC32();
        crc.update(d, 0, footer + 20);
        if ((int) crc.getValue() != stored) throw new CorruptSstException("checksum mismatch");
        if (indexOffL < 4 || indexOffL > bloomOffL || bloomOffL > footer) {
            throw new CorruptSstException("bad section offsets " + indexOffL + "/" + bloomOffL);
        }
        if (count < 0) throw new CorruptSstException("negative count");
        int indexOff = (int) indexOffL;
        int bloomOff = (int) bloomOffL;

        BloomFilter bloom;
        try {
            bloom = BloomFilter.fromBytes(Arrays.copyOfRange(d, bloomOff, footer));
        } catch (IllegalArgumentException e) {
            throw new CorruptSstException("bad bloom block: " + e.getMessage());
        }

        int nIndex = (int) ((count + (long) SstWriter.INDEX_INTERVAL - 1) / SstWriter.INDEX_INTERVAL);
        byte[][] ik = new byte[nIndex][];
        int[] io = new int[nIndex];
        int p = indexOff;
        for (int i = 0; i < nIndex; i++) {
            if (p + 2 > bloomOff) throw new CorruptSstException("index truncated");
            int kl = bb.getShort(p) & 0xFFFF;
            p += 2;
            if (kl == 0 || p + kl + 8 > bloomOff) throw new CorruptSstException("index truncated");
            ik[i] = Arrays.copyOfRange(d, p, p + kl);
            p += kl;
            long off = bb.getLong(p);
            p += 8;
            if (off < 4 || off >= indexOff) throw new CorruptSstException("index offset out of range");
            io[i] = (int) off;
        }
        if (p != bloomOff) throw new CorruptSstException("index size mismatch");

        // Walk every entry: structure, ordering, count and index consistency.
        int pos = 4;
        byte[] prev = null;
        for (int i = 0; i < count; i++) {
            if (pos + 6 > indexOff) throw new CorruptSstException("data truncated at entry " + i);
            int kl = bb.getShort(pos) & 0xFFFF;
            int vl = bb.getInt(pos + 2);
            long end = (long) pos + 6 + kl + Math.max(vl, 0);
            if (kl == 0 || vl < -1 || end > indexOff) throw new CorruptSstException("bad entry " + i);
            byte[] k = Arrays.copyOfRange(d, pos + 6, pos + 6 + kl);
            if (prev != null && Arrays.compareUnsigned(prev, k) >= 0) throw new CorruptSstException("keys out of order at " + i);
            if (i % SstWriter.INDEX_INTERVAL == 0) {
                int slot = i / SstWriter.INDEX_INTERVAL;
                if (io[slot] != pos || !Arrays.equals(ik[slot], k)) throw new CorruptSstException("index mismatch at entry " + i);
            }
            prev = k;
            pos = (int) end;
        }
        if (pos != indexOff) throw new CorruptSstException("trailing bytes in data section");
        return new SstReader(d, indexOff, count, ik, io, bloom);
    }

    public int count() {
        return count;
    }

    /** Looks up one key: Bloom filter, then sparse-index binary search, then a scan of one block. */
    public Lookup get(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        if (count == 0 || !bloom.mightContain(k)) return Lookup.ABSENT;
        int slot = floorSlot(k);
        if (slot < 0) return Lookup.ABSENT;
        int pos = indexOffsets[slot];
        int limit = slot + 1 < indexOffsets.length ? indexOffsets[slot + 1] : dataEnd;
        while (pos < limit) {
            int kl = Short.toUnsignedInt(ByteBuffer.wrap(data).getShort(pos));
            int vl = ByteBuffer.wrap(data).getInt(pos + 2);
            int c = Arrays.compareUnsigned(data, pos + 6, pos + 6 + kl, k, 0, k.length);
            if (c == 0) {
                return vl < 0 ? Lookup.DELETED
                        : new Lookup(Lookup.Kind.FOUND, Arrays.copyOfRange(data, pos + 6 + kl, pos + 6 + kl + vl));
            }
            if (c > 0) return Lookup.ABSENT;
            pos += 6 + kl + Math.max(vl, 0);
        }
        return Lookup.ABSENT;
    }

    /** Greatest index slot whose key is <= k, or -1. */
    private int floorSlot(byte[] k) {
        int lo = 0, hi = indexKeys.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(indexKeys[mid], k) <= 0) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Entries with {@code from <= key < toExclusive}; a null bound is open. */
    public List<Entry> scan(String from, String toExclusive) {
        byte[] f = from == null ? null : from.getBytes(StandardCharsets.UTF_8);
        byte[] t = toExclusive == null ? null : toExclusive.getBytes(StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        if (count == 0) return out;
        int pos = 4;
        if (f != null) {
            int slot = floorSlot(f);
            if (slot >= 0) pos = indexOffsets[slot];
        }
        ByteBuffer bb = ByteBuffer.wrap(data);
        while (pos < dataEnd) {
            int kl = Short.toUnsignedInt(bb.getShort(pos));
            int vl = bb.getInt(pos + 2);
            int kStart = pos + 6;
            int next = kStart + kl + Math.max(vl, 0);
            if (t != null && Arrays.compareUnsigned(data, kStart, kStart + kl, t, 0, t.length) >= 0) break;
            if (f == null || Arrays.compareUnsigned(data, kStart, kStart + kl, f, 0, f.length) >= 0) {
                byte[] v = vl < 0 ? null : Arrays.copyOfRange(data, kStart + kl, next);
                out.add(new Entry(new String(data, kStart, kl, StandardCharsets.UTF_8), v));
            }
            pos = next;
        }
        return out;
    }

    public List<Entry> entries() {
        return scan(null, null);
    }
}
