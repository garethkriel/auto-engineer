package ae.btree;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** Fixed-size pages stored contiguously in a file. Page numbers start at 0. */
public final class PageFile implements Closeable {
    private final RandomAccessFile file;
    private final int pageSize;
    private int pageCount;
    private long reads;
    private long writes;

    public PageFile(Path path, int pageSize) throws IOException {
        if (pageSize < 16) throw new IllegalArgumentException("page size too small: " + pageSize);
        this.pageSize = pageSize;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        long len = file.length();
        if (len % pageSize != 0) {
            file.close();
            throw new IOException("file length " + len + " is not a multiple of page size " + pageSize);
        }
        this.pageCount = (int) (len / pageSize);
    }

    public int pageSize() { return pageSize; }

    public int pageCount() { return pageCount; }

    public long reads() { return reads; }

    public long writes() { return writes; }

    /** Extends the file by one zeroed page and returns its number. */
    public int allocate() throws IOException {
        int p = pageCount;
        file.seek((long) p * pageSize);
        file.write(new byte[pageSize]);
        pageCount++;
        return p;
    }

    public void read(int page, byte[] into) throws IOException {
        check(page);
        file.seek((long) page * pageSize);
        file.readFully(into, 0, pageSize);
        reads++;
    }

    public void write(int page, byte[] data) throws IOException {
        check(page);
        file.seek((long) page * pageSize);
        file.write(data, 0, pageSize);
        writes++;
    }

    private void check(int page) {
        if (page < 0 || page >= pageCount) throw new IllegalArgumentException("no such page " + page);
    }

    @Override
    public void close() throws IOException { file.close(); }
}
