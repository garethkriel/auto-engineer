package ae.erasure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** File-backed erasure-coded blob store: k data + m parity shards spread over k+m node directories. */
public final class BlobStore {
    /** Bytes before the payload in each shard file. */
    public static final int HEADER_SIZE = 38;
    private static final int CRC_COVERED = HEADER_SIZE - 4;
    private static final int MAGIC = 0x45525331; // "ERS1"
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final int k;
    private final int m;
    private final ReedSolomon codec;

    public BlobStore(Path root, int k, int m) {
        this.root = Objects.requireNonNull(root);
        this.codec = new ReedSolomon(k, m);
        this.k = k;
        this.m = m;
    }

    private int nodes() {
        return k + m;
    }

    /** A parsed, internally valid shard. */
    private record Shard(long blobLen, long putId, byte[] payload) { }

    public void put(String name, byte[] bytes) throws IOException {
        checkName(name);
        Objects.requireNonNull(bytes);
        int shardLen = (bytes.length + k - 1) / k;
        byte[][] data = new byte[k][shardLen];
        for (int i = 0; i < k; i++) {
            int from = i * shardLen;
            int n = Math.max(0, Math.min(shardLen, bytes.length - from));
            if (n > 0) {
                System.arraycopy(bytes, from, data[i], 0, n);
            }
        }
        byte[][] parity = codec.encode(data);
        long putId = RANDOM.nextLong();
        for (int i = 0; i < nodes(); i++) {
            byte[] payload = i < k ? data[i] : parity[i - k];
            write(i, name, encodeShard(i, bytes.length, putId, payload));
        }
    }

    public byte[] get(String name) throws IOException {
        checkName(name);
        Loaded l = load(name);
        byte[][] shards = l.payloads();
        if (l.validCount() < k) {
            throw unrecoverable(name, l);
        }
        codec.reconstruct(shards, l.present());
        byte[] out = new byte[(int) l.blobLen()];
        int shardLen = (out.length + k - 1) / k;
        for (int i = 0; i < k && shardLen > 0; i++) {
            int from = i * shardLen;
            int n = Math.min(shardLen, out.length - from);
            if (n > 0) {
                System.arraycopy(shards[i], 0, out, from, n);
            }
        }
        return out;
    }

    public List<ShardState> status(String name) throws IOException {
        checkName(name);
        return load(name).states();
    }

    /** Rewrites every shard that is not OK; returns the repaired node indexes, ascending. */
    public List<Integer> repair(String name) throws IOException {
        checkName(name);
        Loaded l = load(name);
        if (l.validCount() < k) {
            throw unrecoverable(name, l);
        }
        List<Integer> repaired = new ArrayList<>();
        if (l.validCount() == nodes()) {
            return repaired;
        }
        byte[][] shards = l.payloads();
        codec.reconstruct(shards, l.present());
        for (int i = 0; i < nodes(); i++) {
            if (!l.present()[i]) {
                write(i, name, encodeShard(i, l.blobLen(), l.putId(), shards[i]));
                repaired.add(i);
            }
        }
        return repaired;
    }

    public boolean delete(String name) throws IOException {
        checkName(name);
        boolean any = false;
        for (int i = 0; i < nodes(); i++) {
            any |= Files.deleteIfExists(path(i, name));
        }
        return any;
    }

    // ---- internals ----

    private record Loaded(List<ShardState> states, byte[][] payloads, boolean[] present, long blobLen, long putId,
                          int validCount, boolean anyFile) { }

    private Loaded load(String name) throws IOException {
        int n = nodes();
        Shard[] parsed = new Shard[n];
        boolean[] exists = new boolean[n];
        boolean anyFile = false;
        for (int i = 0; i < n; i++) {
            Path p = path(i, name);
            if (Files.exists(p)) {
                exists[i] = true;
                anyFile = true;
                parsed[i] = parse(i, Files.readAllBytes(p));
            }
        }
        if (!anyFile) {
            throw new NoSuchElementException("no such blob: " + name);
        }
        Map<Long, Integer> votes = new HashMap<>();
        for (Shard s : parsed) {
            if (s != null) {
                votes.merge(s.putId(), 1, Integer::sum);
            }
        }
        long best = 0;
        int bestVotes = 0;
        for (Map.Entry<Long, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes || (e.getValue() == bestVotes && e.getKey() > best)) {
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        List<ShardState> states = new ArrayList<>();
        byte[][] payloads = new byte[n][];
        boolean[] present = new boolean[n];
        long blobLen = 0;
        int valid = 0;
        for (int i = 0; i < n; i++) {
            if (!exists[i]) {
                states.add(ShardState.MISSING);
            } else if (parsed[i] != null && parsed[i].putId() == best) {
                states.add(ShardState.OK);
                payloads[i] = parsed[i].payload();
                present[i] = true;
                blobLen = parsed[i].blobLen();
                valid++;
            } else {
                states.add(ShardState.CORRUPT);
            }
        }
        return new Loaded(states, payloads, present, blobLen, best, valid, true);
    }

    private UnrecoverableBlobException unrecoverable(String name, Loaded l) {
        return new UnrecoverableBlobException("blob " + name + ": only " + l.validCount() + " of " + nodes()
                + " shards valid, need " + k + " (" + l.states() + ")");
    }

    private byte[] encodeShard(int index, long blobLen, long putId, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        b.putInt(MAGIC).putShort((short) k).putShort((short) m).putShort((short) index);
        b.putLong(blobLen).putLong(putId).putInt(payload.length);
        CRC32 pc = new CRC32();
        pc.update(payload);
        b.putInt((int) pc.getValue());
        CRC32 hc = new CRC32();
        hc.update(b.array(), 0, CRC_COVERED);
        b.putInt((int) hc.getValue());
        b.put(payload);
        return b.array();
    }

    /** Returns the shard if every check passes, else null. */
    private Shard parse(int node, byte[] file) {
        if (file.length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(file);
        CRC32 hc = new CRC32();
        hc.update(file, 0, CRC_COVERED);
        if (b.getInt(CRC_COVERED) != (int) hc.getValue() || b.getInt(0) != MAGIC) {
            return null;
        }
        if ((b.getShort(4) & 0xFFFF) != k || (b.getShort(6) & 0xFFFF) != m || (b.getShort(8) & 0xFFFF) != node) {
            return null;
        }
        long blobLen = b.getLong(10);
        long putId = b.getLong(18);
        int payloadLen = b.getInt(26);
        if (blobLen < 0 || blobLen > Integer.MAX_VALUE || payloadLen < 0
                || payloadLen != (blobLen + k - 1) / k || file.length - HEADER_SIZE != payloadLen) {
            return null;
        }
        byte[] payload = Arrays.copyOfRange(file, HEADER_SIZE, file.length);
        CRC32 pc = new CRC32();
        pc.update(payload);
        if (b.getInt(30) != (int) pc.getValue()) {
            return null;
        }
        return new Shard(blobLen, putId, payload);
    }

    private void write(int node, String name, byte[] content) throws IOException {
        Path target = path(node, name);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(name + ".shard.tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path path(int node, String name) {
        return root.resolve("node-" + node).resolve(name + ".shard");
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new IllegalArgumentException("bad blob name: " + name);
        }
    }
}
