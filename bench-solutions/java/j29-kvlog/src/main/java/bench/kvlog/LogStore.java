package bench.kvlog;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * A small crash-safe key-value store backed by one append-only log file.
 *
 * <p>Record layout (big endian): {@code type(1) keyLen(4) valueLen(4) key value crc32(4)}, where the
 * CRC32 covers every preceding byte of the record. Type 1 is a put, type 2 a delete.
 */
public final class LogStore implements AutoCloseable {
    private static final String LOG_NAME = "data.log";
    private static final String TMP_NAME = "data.log.compact.tmp";
    private static final int MAX_KEY_CHARS = 1000;
    private static final int MAX_KEY_BYTES = 4 * MAX_KEY_CHARS;
    private static final int MAX_VALUE_BYTES = 1_048_576;
    private static final byte PUT = 1;
    private static final byte DEL = 2;
    private static final int HEADER = 9;

    private final Path dir;
    private final Path logPath;
    private final TreeMap<String, byte[]> map = new TreeMap<>();
    private FileChannel channel;
    private long end;
    private boolean closed;

    private LogStore(Path dir) {
        this.dir = dir;
        this.logPath = dir.resolve(LOG_NAME);
    }

    public static LogStore open(Path dir) throws IOException {
        if (dir == null) {
            throw new IllegalArgumentException("dir is null");
        }
        Files.createDirectories(dir);
        LogStore s = new LogStore(dir);
        Files.deleteIfExists(dir.resolve(TMP_NAME)); // leftover of an interrupted compaction
        s.channel = FileChannel.open(s.logPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            s.recover();
        } catch (IOException | RuntimeException e) {
            s.channel.close();
            throw e;
        }
        return s;
    }

    // ------------------------------------------------------------------ recovery

    private void recover() throws IOException {
        long fileSize = channel.size();
        long good = 0;
        InputStream raw = Channels.newInputStream(channel.position(0));
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] hdr = new byte[HEADER];
        CRC32 crc = new CRC32();
        scan:
        while (good < fileSize) {
            try {
                in.readFully(hdr);
            } catch (EOFException e) {
                break;
            }
            ByteBuffer h = ByteBuffer.wrap(hdr);
            byte type = h.get();
            int keyLen = h.getInt();
            int valLen = h.getInt();
            if ((type != PUT && type != DEL) || keyLen < 1 || keyLen > MAX_KEY_BYTES
                    || valLen < 0 || valLen > MAX_VALUE_BYTES || (type == DEL && valLen != 0)) {
                break;
            }
            long recLen = HEADER + (long) keyLen + valLen + 4;
            if (good + recLen > fileSize) {
                break; // incomplete
            }
            byte[] body = new byte[keyLen + valLen + 4];
            try {
                in.readFully(body);
            } catch (EOFException e) {
                break scan;
            }
            crc.reset();
            crc.update(hdr, 0, HEADER);
            crc.update(body, 0, keyLen + valLen);
            long stored = ((body[keyLen + valLen] & 0xFFL) << 24) | ((body[keyLen + valLen + 1] & 0xFFL) << 16)
                    | ((body[keyLen + valLen + 2] & 0xFFL) << 8) | (body[keyLen + valLen + 3] & 0xFFL);
            if (crc.getValue() != stored) {
                break;
            }
            String key;
            try {
                key = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(body, 0, keyLen)).toString();
            } catch (CharacterCodingException e) {
                break;
            }
            if (key.length() > MAX_KEY_CHARS) {
                break;
            }
            if (type == PUT) {
                byte[] v = new byte[valLen];
                System.arraycopy(body, keyLen, v, 0, valLen);
                map.put(key, v);
            } else {
                map.remove(key);
            }
            good += recLen;
        }
        if (good < fileSize) {
            channel.truncate(good);
        }
        end = good;
    }

    // ------------------------------------------------------------------ operations

    private static void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid key");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < key.length() && Character.isLowSurrogate(key.charAt(i + 1))) {
                    i++;
                } else {
                    throw new IllegalArgumentException("key has unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("key has unpaired surrogate");
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    private static ByteBuffer encode(byte type, byte[] keyBytes, byte[] value) {
        int total = HEADER + keyBytes.length + value.length + 4;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put(type);
        b.putInt(keyBytes.length);
        b.putInt(value.length);
        b.put(keyBytes);
        b.put(value);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, total - 4);
        b.putInt((int) crc.getValue());
        b.flip();
        return b;
    }

    private void append(ByteBuffer rec) throws IOException {
        long start = end;
        try {
            long pos = start;
            while (rec.hasRemaining()) {
                pos += channel.write(rec, pos);
            }
            end = pos;
        } catch (IOException | RuntimeException e) {
            try {
                channel.truncate(start);
            } catch (IOException ignored) {
                // best effort; recovery will drop a torn record anyway
            }
            throw e;
        }
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        ensureOpen();
        checkKey(key);
        if (value == null || value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("invalid value");
        }
        byte[] copy = value.clone();
        append(encode(PUT, key.getBytes(StandardCharsets.UTF_8), copy));
        map.put(key, copy);
    }

    public synchronized byte[] get(String key) {
        ensureOpen();
        checkKey(key);
        byte[] v = map.get(key);
        return v == null ? null : v.clone();
    }

    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        checkKey(key);
        if (!map.containsKey(key)) {
            return;
        }
        append(encode(DEL, key.getBytes(StandardCharsets.UTF_8), new byte[0]));
        map.remove(key);
    }

    public synchronized int size() {
        ensureOpen();
        return map.size();
    }

    public synchronized SortedSet<String> keys() {
        ensureOpen();
        return Collections.unmodifiableSortedSet(new TreeSet<>(map.keySet()));
    }

    public synchronized void compact() throws IOException {
        ensureOpen();
        Path tmp = dir.resolve(TMP_NAME);
        long newEnd = 0;
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, byte[]> e : map.entrySet()) {
                ByteBuffer rec = encode(PUT, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
                while (rec.hasRemaining()) {
                    newEnd += out.write(rec, newEnd);
                }
            }
            out.force(true);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        try {
            try {
                Files.move(tmp, logPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("atomic move not supported", ex);
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        FileChannel old = channel;
        channel = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        end = newEnd;
        try {
            old.close();
        } catch (IOException ignored) {
            // the old file is already replaced
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        channel.close();
    }
}
