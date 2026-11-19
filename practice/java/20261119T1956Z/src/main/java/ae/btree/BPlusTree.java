package ae.btree;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Disk-backed B+tree mapping {@code long} keys to {@code long} values, stored in fixed-size pages
 * accessed through a {@link BufferPool}. Single-threaded. See TASK.md for the page layout.
 */
public final class BPlusTree implements Closeable {
    /** A key/value pair returned by {@link #range}. */
    public record Entry(long key, long value) {}

    private static final int MAGIC = 0xB7EE_0001;
    private static final byte LEAF = 1;
    private static final byte INTERNAL = 2;
    private static final byte FREE = 3;
    private static final int LEAF_HEADER = 11;
    private static final int INTERNAL_HEADER = 7;

    private final PageFile file;
    private final BufferPool pool;
    private final int pageSize;
    private final int leafCap;
    private final int internalCap;

    private int root;
    private int freeHead;
    private long size;

    // Scratch results of the recursive operations (single-threaded).
    private Long lastOld;
    private int splitPage;
    private long splitKey;

    private BPlusTree(PageFile file, BufferPool pool, int pageSize) {
        this.file = file;
        this.pool = pool;
        this.pageSize = pageSize;
        this.leafCap = (pageSize - LEAF_HEADER) / 16;
        this.internalCap = (pageSize - INTERNAL_HEADER) / 12;
    }

    public static BPlusTree open(Path path, int pageSize, int poolFrames) throws IOException {
        if (pageSize < 64) throw new IllegalArgumentException("page size must be >= 64");
        if (poolFrames < 4) throw new IllegalArgumentException("pool needs >= 4 frames");
        PageFile pf = new PageFile(path, pageSize);
        BufferPool pool = new BufferPool(pf, poolFrames);
        BPlusTree t = new BPlusTree(pf, pool, pageSize);
        try {
            if (pf.pageCount() == 0) {
                pool.unpin(pool.allocate()); // meta page, written by writeMeta
                t.writeMeta();
            } else {
                t.readMeta();
            }
        } catch (IOException | RuntimeException e) {
            pf.close();
            throw e;
        }
        return t;
    }

    // ---- meta ----

    private void readMeta() throws IOException {
        BufferPool.Frame f = pool.fetch(0);
        try {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            if (b.getInt(0) != MAGIC) throw new IOException("bad magic number");
            if (b.getInt(4) != pageSize) {
                throw new IOException("page size mismatch: file has " + b.getInt(4) + ", opened with " + pageSize);
            }
            root = b.getInt(8);
            int count = b.getInt(12);
            freeHead = b.getInt(16);
            size = b.getLong(20);
            if (count != file.pageCount() || root < 0 || root >= count || freeHead < 0 || freeHead >= count
                    || size < 0) {
                throw new IOException("inconsistent meta page");
            }
        } finally {
            pool.unpin(f);
        }
    }

    private void writeMeta() throws IOException {
        BufferPool.Frame f = pool.fetch(0);
        ByteBuffer b = ByteBuffer.wrap(f.data());
        b.putInt(0, MAGIC);
        b.putInt(4, pageSize);
        b.putInt(8, root);
        b.putInt(12, file.pageCount());
        b.putInt(16, freeHead);
        b.putLong(20, size);
        f.markDirty();
        pool.unpin(f);
    }

    public void flush() throws IOException {
        writeMeta();
        pool.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
        } finally {
            file.close();
        }
    }

    public long size() { return size; }

    public int pageCount() { return file.pageCount(); }

    public long physicalReads() { return file.reads(); }

    public int height() throws IOException {
        int h = 0;
        int p = root;
        while (p != 0) {
            h++;
            BufferPool.Frame f = pool.fetch(p);
            ByteBuffer b = ByteBuffer.wrap(f.data());
            int next = b.get(0) == INTERNAL ? b.getInt(3) : 0;
            pool.unpin(f);
            p = next;
        }
        return h;
    }

    // ---- page helpers ----

    private BufferPool.Frame newPage(byte type) throws IOException {
        BufferPool.Frame f;
        if (freeHead != 0) {
            f = pool.fetch(freeHead);
            freeHead = ByteBuffer.wrap(f.data()).getInt(1);
            java.util.Arrays.fill(f.data(), (byte) 0);
        } else {
            f = pool.allocate();
        }
        f.data()[0] = type;
        f.markDirty();
        return f;
    }

    /** Marks a pinned frame as free and pushes it on the free list. */
    private void release(BufferPool.Frame f) {
        java.util.Arrays.fill(f.data(), (byte) 0);
        f.data()[0] = FREE;
        ByteBuffer.wrap(f.data()).putInt(1, freeHead);
        freeHead = f.pageNo();
        f.markDirty();
    }

    private static int count(ByteBuffer b) { return b.getShort(1); }

    private static void setCount(ByteBuffer b, int n) { b.putShort(1, (short) n); }

    // leaf accessors
    private static long lKey(ByteBuffer b, int i) { return b.getLong(LEAF_HEADER + i * 16); }

    private static long lVal(ByteBuffer b, int i) { return b.getLong(LEAF_HEADER + i * 16 + 8); }

    private static void lSet(ByteBuffer b, int i, long k, long v) {
        b.putLong(LEAF_HEADER + i * 16, k);
        b.putLong(LEAF_HEADER + i * 16 + 8, v);
    }

    private static int lNext(ByteBuffer b) { return b.getInt(3); }

    private static int lPrev(ByteBuffer b) { return b.getInt(7); }

    // internal accessors: child(0) at 3, then (key i, child i+1)
    private static long iKey(ByteBuffer b, int i) { return b.getLong(INTERNAL_HEADER + i * 12); }

    private static int iChild(ByteBuffer b, int i) {
        return i == 0 ? b.getInt(3) : b.getInt(INTERNAL_HEADER + (i - 1) * 12 + 8);
    }

    private static void iSetChild(ByteBuffer b, int i, int c) {
        if (i == 0) b.putInt(3, c);
        else b.putInt(INTERNAL_HEADER + (i - 1) * 12 + 8, c);
    }

    private static void iSetKey(ByteBuffer b, int i, long k) { b.putLong(INTERNAL_HEADER + i * 12, k); }

    /** Number of keys in leaf that are < key (lower bound). */
    private static int leafLowerBound(ByteBuffer b, long key) {
        int lo = 0, hi = count(b);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lKey(b, mid) < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Index of the child covering key: number of separator keys <= key. */
    private static int childIndex(ByteBuffer b, long key) {
        int lo = 0, hi = count(b);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (iKey(b, mid) <= key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ---- get / range ----

    private BufferPool.Frame findLeaf(long key) throws IOException {
        int p = root;
        while (true) {
            BufferPool.Frame f = pool.fetch(p);
            ByteBuffer b = ByteBuffer.wrap(f.data());
            if (b.get(0) == LEAF) return f;
            if (b.get(0) != INTERNAL) {
                pool.unpin(f);
                throw new IllegalStateException("unexpected page type at " + p);
            }
            p = iChild(b, childIndex(b, key));
            pool.unpin(f);
        }
    }

    public Optional<Long> get(long key) throws IOException {
        if (root == 0) return Optional.empty();
        BufferPool.Frame f = findLeaf(key);
        try {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            int i = leafLowerBound(b, key);
            return i < count(b) && lKey(b, i) == key ? Optional.of(lVal(b, i)) : Optional.empty();
        } finally {
            pool.unpin(f);
        }
    }

    public List<Entry> range(long lo, long hiInclusive) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (root == 0 || lo > hiInclusive) return out;
        BufferPool.Frame f = findLeaf(lo);
        while (true) {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            int n = count(b);
            boolean done = false;
            for (int i = leafLowerBound(b, lo); i < n; i++) {
                long k = lKey(b, i);
                if (k > hiInclusive) {
                    done = true;
                    break;
                }
                out.add(new Entry(k, lVal(b, i)));
            }
            int next = lNext(b);
            pool.unpin(f);
            if (done || next == 0) return out;
            f = pool.fetch(next);
        }
    }

    // ---- put ----

    public Optional<Long> put(long key, long value) throws IOException {
        lastOld = null;
        if (root == 0) {
            BufferPool.Frame f = newPage(LEAF);
            ByteBuffer b = ByteBuffer.wrap(f.data());
            lSet(b, 0, key, value);
            setCount(b, 1);
            root = f.pageNo();
            pool.unpin(f);
            size = 1;
            return Optional.empty();
        }
        splitPage = 0;
        insert(root, key, value);
        if (splitPage != 0) {
            BufferPool.Frame f = newPage(INTERNAL);
            ByteBuffer b = ByteBuffer.wrap(f.data());
            iSetChild(b, 0, root);
            iSetKey(b, 0, splitKey);
            iSetChild(b, 1, splitPage);
            setCount(b, 1);
            root = f.pageNo();
            pool.unpin(f);
            splitPage = 0;
        }
        if (lastOld == null) size++;
        return Optional.ofNullable(lastOld);
    }

    private void insert(int page, long key, long value) throws IOException {
        BufferPool.Frame f = pool.fetch(page);
        try {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            if (b.get(0) == LEAF) insertLeaf(f, b, key, value);
            else insertInternal(f, b, key, value);
        } finally {
            pool.unpin(f);
        }
    }

    private void insertLeaf(BufferPool.Frame f, ByteBuffer b, long key, long value) throws IOException {
        int n = count(b);
        int pos = leafLowerBound(b, key);
        if (pos < n && lKey(b, pos) == key) {
            lastOld = lVal(b, pos);
            lSet(b, pos, key, value);
            f.markDirty();
            return;
        }
        long[] ks = new long[n + 1];
        long[] vs = new long[n + 1];
        for (int i = 0, j = 0; i <= n; i++) {
            if (i == pos) {
                ks[i] = key;
                vs[i] = value;
            } else {
                ks[i] = lKey(b, j);
                vs[i] = lVal(b, j);
                j++;
            }
        }
        f.markDirty();
        if (n < leafCap) {
            for (int i = 0; i <= n; i++) lSet(b, i, ks[i], vs[i]);
            setCount(b, n + 1);
            return;
        }
        int leftN = (n + 1 + 1) / 2;
        int rightN = n + 1 - leftN;
        BufferPool.Frame rf = newPage(LEAF);
        try {
            ByteBuffer rb = ByteBuffer.wrap(rf.data());
            for (int i = 0; i < leftN; i++) lSet(b, i, ks[i], vs[i]);
            setCount(b, leftN);
            for (int i = 0; i < rightN; i++) lSet(rb, i, ks[leftN + i], vs[leftN + i]);
            setCount(rb, rightN);
            int oldNext = lNext(b);
            rb.putInt(3, oldNext);
            rb.putInt(7, f.pageNo());
            b.putInt(3, rf.pageNo());
            if (oldNext != 0) {
                BufferPool.Frame nf = pool.fetch(oldNext);
                ByteBuffer.wrap(nf.data()).putInt(7, rf.pageNo());
                nf.markDirty();
                pool.unpin(nf);
            }
            splitKey = ks[leftN];
            splitPage = rf.pageNo();
        } finally {
            pool.unpin(rf);
        }
    }

    private void insertInternal(BufferPool.Frame f, ByteBuffer b, long key, long value) throws IOException {
        int idx = childIndex(b, key);
        splitPage = 0;
        insert(iChild(b, idx), key, value);
        if (splitPage == 0) return;
        int n = count(b);
        long[] ks = new long[n + 1];
        int[] cs = new int[n + 2];
        for (int i = 0, j = 0; i <= n; i++) {
            if (i == idx) {
                ks[i] = splitKey;
            } else {
                ks[i] = iKey(b, j++);
            }
        }
        for (int i = 0, j = 0; i <= n + 1; i++) {
            if (i == idx + 1) {
                cs[i] = splitPage;
            } else {
                cs[i] = iChild(b, j++);
            }
        }
        f.markDirty();
        if (n < internalCap) {
            writeInternal(b, ks, cs, 0, n + 1);
            splitPage = 0;
            return;
        }
        int mid = (n + 1) / 2;
        BufferPool.Frame rf = newPage(INTERNAL);
        try {
            ByteBuffer rb = ByteBuffer.wrap(rf.data());
            writeInternal(b, ks, cs, 0, mid);
            writeInternal(rb, ks, cs, mid + 1, n + 1 - (mid + 1));
            splitKey = ks[mid];
            splitPage = rf.pageNo();
        } finally {
            pool.unpin(rf);
        }
    }

    /** Writes keys[from..from+cnt) and children[from..from+cnt] into an internal node page. */
    private static void writeInternal(ByteBuffer b, long[] ks, int[] cs, int from, int cnt) {
        iSetChild(b, 0, cs[from]);
        for (int i = 0; i < cnt; i++) {
            iSetKey(b, i, ks[from + i]);
            iSetChild(b, i + 1, cs[from + i + 1]);
        }
        setCount(b, cnt);
    }

    // ---- remove ----

    public Optional<Long> remove(long key) throws IOException {
        lastOld = null;
        if (root == 0) return Optional.empty();
        boolean empty = delete(root, key);
        if (lastOld == null) return Optional.empty();
        size--;
        if (empty) {
            root = 0;
        } else {
            // collapse a root internal node that has a single child
            while (true) {
                BufferPool.Frame f = pool.fetch(root);
                ByteBuffer b = ByteBuffer.wrap(f.data());
                if (b.get(0) == INTERNAL && count(b) == 0) {
                    int only = iChild(b, 0);
                    release(f);
                    pool.unpin(f);
                    root = only;
                } else {
                    pool.unpin(f);
                    break;
                }
            }
        }
        return Optional.of(lastOld);
    }

    /** Deletes key under page; returns true when the page became empty (and has been freed). */
    private boolean delete(int page, long key) throws IOException {
        BufferPool.Frame f = pool.fetch(page);
        try {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            if (b.get(0) == LEAF) return deleteLeaf(f, b, key);
            int idx = childIndex(b, key);
            boolean childEmpty = delete(iChild(b, idx), key);
            if (!childEmpty) return false;
            int n = count(b);
            if (n == 0) {
                release(f);
                return true;
            }
            // drop child idx together with one adjacent separator key
            int keyToDrop = idx == 0 ? 0 : idx - 1;
            if (idx == 0) iSetChild(b, 0, iChild(b, 1));
            for (int i = keyToDrop; i < n - 1; i++) {
                iSetKey(b, i, iKey(b, i + 1));
                iSetChild(b, i + 1, iChild(b, i + 2));
            }
            setCount(b, n - 1);
            f.markDirty();
            return false;
        } finally {
            pool.unpin(f);
        }
    }

    private boolean deleteLeaf(BufferPool.Frame f, ByteBuffer b, long key) throws IOException {
        int n = count(b);
        int pos = leafLowerBound(b, key);
        if (pos >= n || lKey(b, pos) != key) return false;
        lastOld = lVal(b, pos);
        for (int i = pos; i < n - 1; i++) lSet(b, i, lKey(b, i + 1), lVal(b, i + 1));
        setCount(b, n - 1);
        f.markDirty();
        if (n - 1 > 0) return false;
        int prev = lPrev(b), next = lNext(b);
        if (prev != 0) relink(prev, 3, next);
        if (next != 0) relink(next, 7, prev);
        release(f);
        return true;
    }

    private void relink(int page, int offset, int target) throws IOException {
        BufferPool.Frame f = pool.fetch(page);
        ByteBuffer.wrap(f.data()).putInt(offset, target);
        f.markDirty();
        pool.unpin(f);
    }

    // ---- invariant checker ----

    /** Verifies every structural invariant; throws IllegalStateException describing the first violation. */
    public void check() throws IOException {
        List<Integer> leaves = new ArrayList<>();
        long[] total = {0};
        int[] pages = {0};
        int[] leafDepth = {-1};
        if (root != 0) checkNode(root, 1, false, 0, false, 0, leaves, total, pages, leafDepth);
        if (total[0] != size) throw new IllegalStateException("size " + size + " but leaves hold " + total[0]);
        for (int i = 0; i < leaves.size(); i++) {
            BufferPool.Frame f = pool.fetch(leaves.get(i));
            ByteBuffer b = ByteBuffer.wrap(f.data());
            int prev = lPrev(b), next = lNext(b);
            pool.unpin(f);
            int expPrev = i == 0 ? 0 : leaves.get(i - 1);
            int expNext = i == leaves.size() - 1 ? 0 : leaves.get(i + 1);
            if (prev != expPrev || next != expNext) {
                throw new IllegalStateException("leaf chain broken at page " + leaves.get(i));
            }
        }
        int free = 0;
        for (int p = freeHead; p != 0; ) {
            if (++free > file.pageCount()) throw new IllegalStateException("free list cycle");
            BufferPool.Frame f = pool.fetch(p);
            ByteBuffer b = ByteBuffer.wrap(f.data());
            byte type = b.get(0);
            int next = b.getInt(1);
            pool.unpin(f);
            if (type != FREE) throw new IllegalStateException("page " + p + " on free list is not free");
            p = next;
        }
        if (1 + pages[0] + free != file.pageCount()) {
            throw new IllegalStateException("page leak: meta 1 + tree " + pages[0] + " + free " + free
                    + " != " + file.pageCount());
        }
        if (pool.pinnedCount() != 0) throw new IllegalStateException("frames left pinned: " + pool.pinnedCount());
    }

    private void checkNode(int page, int depth, boolean hasLo, long lo, boolean hasHi, long hi,
                           List<Integer> leaves, long[] total, int[] pages, int[] leafDepth) throws IOException {
        pages[0]++;
        BufferPool.Frame f = pool.fetch(page);
        try {
            ByteBuffer b = ByteBuffer.wrap(f.data());
            int n = count(b);
            if (b.get(0) == LEAF) {
                if (n < 1 || n > leafCap) throw new IllegalStateException("leaf " + page + " bad count " + n);
                for (int i = 0; i < n; i++) {
                    long k = lKey(b, i);
                    if (i > 0 && lKey(b, i - 1) >= k) {
                        throw new IllegalStateException("key order violated in leaf " + page);
                    }
                    if ((hasLo && k < lo) || (hasHi && k >= hi)) {
                        throw new IllegalStateException("key " + k + " out of bounds in leaf " + page);
                    }
                }
                if (leafDepth[0] == -1) leafDepth[0] = depth;
                else if (leafDepth[0] != depth) throw new IllegalStateException("leaves at different depths");
                leaves.add(page);
                total[0] += n;
            } else if (b.get(0) == INTERNAL) {
                if (n < 0 || n > internalCap) throw new IllegalStateException("internal " + page + " bad count " + n);
                for (int i = 0; i < n; i++) {
                    long k = iKey(b, i);
                    if (i > 0 && iKey(b, i - 1) >= k) {
                        throw new IllegalStateException("key order violated in internal " + page);
                    }
                    if ((hasLo && k < lo) || (hasHi && k >= hi)) {
                        throw new IllegalStateException("separator " + k + " out of bounds in " + page);
                    }
                }
                for (int i = 0; i <= n; i++) {
                    boolean cHasLo = i > 0 || hasLo;
                    long cLo = i > 0 ? iKey(b, i - 1) : lo;
                    boolean cHasHi = i < n || hasHi;
                    long cHi = i < n ? iKey(b, i) : hi;
                    checkNode(iChild(b, i), depth + 1, cHasLo, cLo, cHasHi, cHi, leaves, total, pages, leafDepth);
                }
            } else {
                throw new IllegalStateException("page " + page + " has bad type " + b.get(0));
            }
        } finally {
            pool.unpin(f);
        }
    }
}
