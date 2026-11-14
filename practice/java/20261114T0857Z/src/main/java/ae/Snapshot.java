package ae;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable map from relative file path to SHA-256 hex digest of its content. */
public final class Snapshot {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Map<String, String> hashes;

    /** Creates a snapshot from a path-to-hash map (copied, sorted by path). */
    public Snapshot(Map<String, String> hashes) {
        this.hashes = java.util.Collections.unmodifiableSortedMap(
                new TreeMap<>(Objects.requireNonNull(hashes)));
    }

    /** @return unmodifiable, path-sorted view of path to hex digest */
    public Map<String, String> hashes() {
        return hashes;
    }

    /** Snapshots {@code dir}, skipping files whose relative path matches an ignore glob. */
    public static Snapshot of(Path dir, String... ignoreGlobs) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        FileSystem fs = dir.getFileSystem();
        List<PathMatcher> ignores = new ArrayList<>();
        for (String g : ignoreGlobs) {
            ignores.add(fs.getPathMatcher("glob:" + g));
        }
        Map<String, String> out = new TreeMap<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = dir.relativize(file);
                for (PathMatcher m : ignores) {
                    if (m.matches(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                }
                StringBuilder key = new StringBuilder();
                for (Path part : rel) {
                    if (key.length() > 0) {
                        key.append('/');
                    }
                    key.append(part);
                }
                String k = key.toString();
                if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("unsupported character in file name: " + k);
                }
                out.put(k, sha256(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return new Snapshot(out);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares this (older) snapshot with {@code after} (newer). */
    public Diff diff(Snapshot after) {
        Objects.requireNonNull(after);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        for (var e : after.hashes.entrySet()) {
            String old = hashes.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                modified.add(e.getKey());
            }
        }
        for (String k : hashes.keySet()) {
            if (!after.hashes.containsKey(k)) {
                removed.add(k);
            }
        }
        return new Diff(added, removed, modified);
    }

    /** Writes {@code hash<TAB>path} lines atomically (temp file in the same directory, then move). */
    public void write(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        hashes.forEach((p, h) -> sb.append(h).append('\t').append(p).append('\n'));
        Path parent = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".snapshot", ".tmp");
        try {
            Files.writeString(tmp, sb, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Reads a file produced by {@link #write(Path)}; malformed content raises {@link IOException}. */
    public static Snapshot read(Path file) throws IOException {
        Map<String, String> map = new TreeMap<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IOException("line " + lineNo + ": missing tab");
            }
            String hash = line.substring(0, tab);
            String path = line.substring(tab + 1);
            if (!HASH.matcher(hash).matches()) {
                throw new IOException("line " + lineNo + ": bad hash");
            }
            if (path.isEmpty()) {
                throw new IOException("line " + lineNo + ": empty path");
            }
            if (map.put(path, hash) != null) {
                throw new IOException("line " + lineNo + ": duplicate path " + path);
            }
        }
        return new Snapshot(map);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Snapshot s && hashes.equals(s.hashes);
    }

    @Override
    public int hashCode() {
        return hashes.hashCode();
    }

    @Override
    public String toString() {
        return "Snapshot" + hashes;
    }
}
