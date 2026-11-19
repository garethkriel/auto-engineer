package ae.btree;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LRU page cache with pinning. A pinned frame is never evicted. */
public final class BufferPool implements Closeable {
    /** A cached page. Callers must {@link #unpin} every frame they fetch or allocate. */
    public static final class Frame {
        private final int pageNo;
        private final byte[] data;
        private int pins;
        private boolean dirty;
        private long lastUse;

        private Frame(int pageNo, byte[] data) {
            this.pageNo = pageNo;
            this.data = data;
        }

        public int pageNo() { return pageNo; }

        public byte[] data() { return data; }

        public void markDirty() { dirty = true; }
    }

    private final PageFile file;
    private final int capacity;
    private final Map<Integer, Frame> frames = new HashMap<>();
    private long clock;
    private long hits;
    private long misses;

    public BufferPool(PageFile file, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.file = file;
        this.capacity = capacity;
    }

    public long hits() { return hits; }

    public long misses() { return misses; }

    public boolean isCached(int pageNo) { return frames.containsKey(pageNo); }

    public Frame fetch(int pageNo) throws IOException {
        Frame f = frames.get(pageNo);
        if (f != null) {
            hits++;
        } else {
            misses++;
            makeRoom();
            byte[] data = new byte[file.pageSize()];
            file.read(pageNo, data);
            f = new Frame(pageNo, data);
            frames.put(pageNo, f);
        }
        f.pins++;
        f.lastUse = ++clock;
        return f;
    }

    /** Appends a new zeroed page to the file and returns it pinned and dirty. */
    public Frame allocate() throws IOException {
        makeRoom();
        int p = file.allocate();
        Frame f = new Frame(p, new byte[file.pageSize()]);
        f.dirty = true;
        f.pins = 1;
        f.lastUse = ++clock;
        frames.put(p, f);
        return f;
    }

    public void unpin(Frame f) {
        if (f.pins <= 0) throw new IllegalStateException("page " + f.pageNo + " is not pinned");
        f.pins--;
    }

    public int pinnedCount() {
        int n = 0;
        for (Frame f : frames.values()) if (f.pins > 0) n++;
        return n;
    }

    private void makeRoom() throws IOException {
        if (frames.size() < capacity) return;
        Frame victim = null;
        for (Frame f : frames.values()) {
            if (f.pins == 0 && (victim == null || f.lastUse < victim.lastUse)) victim = f;
        }
        if (victim == null) throw new IllegalStateException("all " + capacity + " frames are pinned");
        if (victim.dirty) {
            file.write(victim.pageNo, victim.data);
            victim.dirty = false;
        }
        frames.remove(victim.pageNo);
    }

    /** Writes every dirty frame (in page order) to the file. */
    public void flush() throws IOException {
        List<Frame> dirty = new ArrayList<>();
        for (Frame f : frames.values()) if (f.dirty) dirty.add(f);
        dirty.sort((a, b) -> Integer.compare(a.pageNo, b.pageNo));
        for (Frame f : dirty) {
            file.write(f.pageNo, f.data);
            f.dirty = false;
        }
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
