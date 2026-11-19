package ae.btree;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {
    @TempDir Path dir;

    @Test
    void pageFileRoundTrip() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("a.db"), 64)) {
            int p = pf.allocate();
            assertEquals(0, p);
            byte[] data = new byte[64];
            data[5] = 42;
            pf.write(p, data);
            byte[] back = new byte[64];
            pf.read(p, back);
            assertEquals(42, back[5]);
            assertEquals(1, pf.pageCount());
        }
    }

    @Test
    void hitsAndMissesAreCounted() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("b.db"), 64);
             BufferPool pool = new BufferPool(pf, 2)) {
            BufferPool.Frame a = pool.allocate();
            int pa = a.pageNo();
            pool.unpin(a);
            BufferPool.Frame again = pool.fetch(pa);
            pool.unpin(again);
            assertEquals(1, pool.hits());
            assertEquals(0, pool.misses());
        }
    }

    @Test
    void evictsLeastRecentlyUsedAndWritesBackDirty() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("c.db"), 64);
             BufferPool pool = new BufferPool(pf, 2)) {
            BufferPool.Frame f0 = pool.allocate();
            f0.data()[0] = 11;
            f0.markDirty();
            int p0 = f0.pageNo();
            pool.unpin(f0);
            BufferPool.Frame f1 = pool.allocate();
            int p1 = f1.pageNo();
            pool.unpin(f1);
            // touch p0 so p1 becomes least recently used
            pool.unpin(pool.fetch(p0));
            BufferPool.Frame f2 = pool.allocate(); // evicts p1 (clean-ish), not p0
            pool.unpin(f2);
            assertFalse(pool.isCached(p1), "p1 should be evicted");
            assertTrue(pool.isCached(p0), "p0 recently used must stay");
            // now force p0 out: touch f2 and p-new
            BufferPool.Frame f3 = pool.allocate();
            pool.unpin(f3);
            BufferPool.Frame f4 = pool.allocate();
            pool.unpin(f4);
            assertFalse(pool.isCached(p0));
            BufferPool.Frame back = pool.fetch(p0);
            assertEquals(11, back.data()[0], "dirty page must have been written back");
            pool.unpin(back);
        }
    }

    @Test
    void allPinnedThrows() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("d.db"), 64);
             BufferPool pool = new BufferPool(pf, 2)) {
            pool.allocate();
            pool.allocate();
            IllegalStateException e = assertThrows(IllegalStateException.class, pool::allocate);
            assertTrue(e.getMessage().contains("pinned"), e.getMessage());
        }
    }

    @Test
    void unpinTwiceRejected() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("e.db"), 64);
             BufferPool pool = new BufferPool(pf, 2)) {
            BufferPool.Frame f = pool.allocate();
            pool.unpin(f);
            assertThrows(IllegalStateException.class, () -> pool.unpin(f));
        }
    }

    @Test
    void flushWritesDirtyOnceAndClearsFlag() throws IOException {
        try (PageFile pf = new PageFile(dir.resolve("f.db"), 64);
             BufferPool pool = new BufferPool(pf, 4)) {
            BufferPool.Frame f = pool.allocate();
            f.data()[1] = 7;
            f.markDirty();
            pool.unpin(f);
            long before = pf.writes();
            pool.flush();
            assertEquals(before + 1, pf.writes());
            pool.flush();
            assertEquals(before + 1, pf.writes(), "clean frames are not rewritten");
        }
    }
}
