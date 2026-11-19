package ae.rsync;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Makes the regular files under {@code dst} equal to those under {@code src}, moving only deltas.
 * Every changed file goes through serialised signature and delta messages, as it would on a wire.
 * Files are held in memory, so this suits files up to a few hundred MB.
 */
public final class DirSync {
    private DirSync() {}

    /** Outcome counters. Byte counts are wire sizes of the serialised messages. */
    public record Report(int created, int updated, int unchanged, int deleted,
                         long signatureBytes, long deltaBytes, long literalBytes, long sourceBytes) {}

    public static Report sync(Path src, Path dst, int blockSize) throws IOException {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (!Files.isDirectory(src)) {
            throw new IllegalArgumentException("source is not a directory: " + src);
        }
        Path s = src.toRealPath();
        Path d = Files.exists(dst) ? dst.toRealPath() : dst.toAbsolutePath().normalize();
        if (s.startsWith(d) || d.startsWith(s)) {
            throw new IllegalArgumentException("source and destination must not be the same or nested");
        }

        Map<String, Path> srcFiles = regularFiles(s);
        Files.createDirectories(d);
        Map<String, Path> dstFiles = regularFiles(d);

        int deleted = 0;
        for (Map.Entry<String, Path> e : dstFiles.entrySet()) {
            if (!srcFiles.containsKey(e.getKey())) {
                Files.delete(e.getValue());
                deleted++;
            }
        }
        pruneEmptyDirectories(d);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        long sigBytes = 0;
        long deltaBytes = 0;
        long literal = 0;
        long sourceBytes = 0;
        for (Map.Entry<String, Path> e : srcFiles.entrySet()) {
            Path target = d.resolve(e.getKey());
            boolean exists = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
            byte[] basis = exists ? Files.readAllBytes(target) : new byte[0];
            byte[] data = Files.readAllBytes(e.getValue());
            try {
                // receiver -> sender
                byte[] sigWire = Signature.of(basis, blockSize).toBytes();
                sigBytes += sigWire.length;
                Delta delta = DeltaEncoder.compute(Signature.parse(sigWire), data);
                // sender -> receiver
                byte[] deltaWire = delta.toBytes();
                deltaBytes += deltaWire.length;
                Delta received = Delta.parse(deltaWire);
                sourceBytes += data.length;
                literal += literalBytes(received);
                if (exists && isIdentity(received, basis.length)) {
                    unchanged++;
                    continue;
                }
                writeAtomically(target, Patcher.apply(basis, received));
            } catch (FormatException | PatchException ex) {
                throw new IOException("sync of " + e.getKey() + " failed: " + ex.getMessage(), ex);
            }
            if (exists) {
                updated++;
            } else {
                created++;
            }
        }
        return new Report(created, updated, unchanged, deleted, sigBytes, deltaBytes, literal, sourceBytes);
    }

    private static boolean isIdentity(Delta delta, int basisLength) {
        if (delta.targetLength() != basisLength) {
            return false;
        }
        if (basisLength == 0) {
            return true;
        }
        return delta.ops().size() == 1 && delta.ops().get(0) instanceof Delta.Copy c && c.offset() == 0;
    }

    private static long literalBytes(Delta delta) {
        long n = 0;
        for (Delta.Op op : delta.ops()) {
            if (op instanceof Delta.Literal l) {
                n += l.size();
            }
        }
        return n;
    }

    private static Map<String, Path> regularFiles(Path root) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    StringBuilder key = new StringBuilder();
                    for (Path name : root.relativize(p)) {
                        if (key.length() > 0) {
                            key.append('/');
                        }
                        key.append(name);
                    }
                    out.put(key.toString(), p);
                }
            }
        }
        return out;
    }

    private static void pruneEmptyDirectories(Path root) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path dir : dirs) {
            try {
                Files.delete(dir); // fails when not empty, which is exactly the check we want
            } catch (DirectoryNotEmptyException keep) {
                // still holds files that are in the source
            }
        }
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".rsync-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }
}
