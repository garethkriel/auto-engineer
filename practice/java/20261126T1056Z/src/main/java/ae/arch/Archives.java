package ae.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Tree-level checks and file-system helpers shared by the formats. */
public final class Archives {
    private Archives() {}

    private static String stripSlash(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    /** Rejects duplicate members ({@code a} and {@code a/} collide) and files used as parent directories. */
    public static void checkTree(List<ArchiveEntry> entries) {
        Map<String, Boolean> isDir = new HashMap<>();
        for (ArchiveEntry e : entries) {
            if (isDir.put(stripSlash(e.name()), e.directory()) != null) {
                throw new ArchiveException("duplicate entry: " + e.name());
            }
        }
        for (ArchiveEntry e : entries) {
            String n = stripSlash(e.name());
            for (int i = n.indexOf('/'); i >= 0; i = n.indexOf('/', i + 1)) {
                Boolean parentIsDir = isDir.get(n.substring(0, i));
                if (parentIsDir != null && !parentIsDir) {
                    throw new ArchiveException("file used as parent directory: " + n.substring(0, i) + " (of " + e.name() + ")");
                }
            }
        }
    }

    /** Writes the entries below {@code root}. Never overwrites and never leaves {@code root}. */
    public static void extractTo(Path root, List<ArchiveEntry> entries) throws IOException {
        checkTree(entries);
        Path base = root.toAbsolutePath().normalize();
        Files.createDirectories(base);
        for (ArchiveEntry e : entries) {
            Path target = base.resolve(stripSlash(e.name())).normalize();
            if (!target.startsWith(base) || target.equals(base)) {
                throw new ArchiveException("entry escapes the extraction root: " + e.name());
            }
            if (e.directory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.write(target, e.data(), java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
    }

    /** Reads a directory tree: directories end with {@code /}, parents come before children, sorted by name. */
    public static List<ArchiveEntry> snapshot(Path root) throws IOException {
        List<ArchiveEntry> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.filter(p -> !p.equals(root)).toList();
            Map<String, Path> byName = new java.util.TreeMap<>();
            for (Path p : paths) {
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                byName.put(Files.isDirectory(p) ? rel + "/" : rel, p);
            }
            for (Map.Entry<String, Path> en : byName.entrySet()) {
                out.add(new ArchiveEntry(en.getKey(), en.getKey().endsWith("/") ? new byte[0] : Files.readAllBytes(en.getValue())));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return out;
    }
}
