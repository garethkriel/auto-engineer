package ae.tar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts entries below a destination directory without ever leaving it. The whole entry list is validated
 * (against itself and against what already exists on disk) before the first file is written, so a rejected archive
 * leaves the destination untouched.
 *
 * <p>Limits: setuid/setgid/sticky bits are dropped; owners are not applied; hard links become copies carrying the
 * target's mode and time; mtimes outside 0..2^34 seconds are not applied; the check-then-write window (TOCTOU)
 * against concurrent changes to the destination is not defended.
 */
public final class TarExtractor {
    private static final long MAX_MTIME = 1L << 34;
    private static final PosixFilePermission[] BITS = {
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};

    private TarExtractor() { }

    /** Extracts {@code entries} into the existing directory {@code dest}. */
    public static void extract(List<Entry> entries, Path dest) throws IOException {
        if (!Files.isDirectory(dest)) {
            throw new TarFormatException("destination is not an existing directory: " + dest);
        }
        Path root = dest.toRealPath();
        Map<String, Entry> files = validate(entries, root); // name -> the FILE entry whose content that name carries
        List<Entry> dirs = new ArrayList<>();
        for (Entry e : entries) {
            Path p = root.resolve(e.name());
            switch (e.type()) {
                case DIRECTORY -> {
                    Files.createDirectories(p);
                    dirs.add(e);
                }
                case FILE, HARDLINK -> {
                    Files.createDirectories(p.getParent());
                    Entry src = files.get(e.name());
                    Files.write(p, src.data(), StandardOpenOption.CREATE_NEW);
                    applyMetadata(p, src);
                }
                case SYMLINK -> {
                    Files.createDirectories(p.getParent());
                    Files.createSymbolicLink(p, Path.of(e.linkName()));
                }
                default -> throw new IllegalStateException("unknown type " + e.type());
            }
        }
        for (int i = dirs.size() - 1; i >= 0; i--) { // children are written: now the (possibly read-only) directories
            applyMetadata(root.resolve(dirs.get(i).name()), dirs.get(i));
        }
    }

    private static Map<String, Entry> validate(List<Entry> entries, Path root) throws IOException {
        Map<String, Entry> seen = new HashMap<>();
        Map<String, Entry> files = new HashMap<>();
        Set<String> parents = new HashSet<>();
        for (Entry e : entries) {
            String name = e.name();
            if (seen.containsKey(name)) {
                throw new TarFormatException("duplicate entry name: " + name);
            }
            String[] segs = name.split("/");
            StringBuilder anc = new StringBuilder();
            for (int i = 0; i < segs.length - 1; i++) {
                anc.append(i == 0 ? "" : "/").append(segs[i]);
                Entry a = seen.get(anc.toString());
                if (a != null && a.type() != Entry.Type.DIRECTORY) {
                    throw new TarFormatException("conflict: " + name + " lies below the non-directory entry " + anc);
                }
                parents.add(anc.toString());
            }
            if (e.type() != Entry.Type.DIRECTORY && parents.contains(name)) {
                throw new TarFormatException("conflict: " + name + " is also a parent directory of another entry");
            }
            checkDisk(root, segs, e);
            switch (e.type()) {
                case SYMLINK -> checkSymlinkTarget(e);
                case HARDLINK -> {
                    Entry target = files.get(e.linkName());
                    if (target == null) {
                        throw new TarFormatException("hardlink " + name + " must point at an earlier file entry, not " + e.linkName());
                    }
                    files.put(name, target);
                }
                case FILE -> files.put(name, e);
                default -> { }
            }
            seen.put(name, e);
        }
        return files;
    }

    /** Looks at what already exists on disk along the entry's path, never following links. */
    private static void checkDisk(Path root, String[] segs, Entry e) throws TarFormatException {
        Path cur = root;
        for (int i = 0; i < segs.length; i++) {
            cur = cur.resolve(segs[i]);
            boolean last = i == segs.length - 1;
            if (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                return; // nothing deeper can exist either
            }
            if (Files.isSymbolicLink(cur)) {
                if (last && e.type() != Entry.Type.DIRECTORY) {
                    throw new TarFormatException(e.name() + " already exists (as a symlink)");
                }
                throw new TarFormatException("unsafe: " + e.name() + " passes through an existing symlink");
            }
            boolean isDir = Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS);
            if (last && e.type() != Entry.Type.DIRECTORY) {
                throw new TarFormatException(e.name() + " already exists");
            }
            if (!isDir) {
                throw new TarFormatException("an existing path component of " + e.name() + " is not a directory");
            }
        }
    }

    /**
     * A symlink target may only be relative and may use ".." only as a leading run no longer than the link's depth.
     * ".." after a name is refused because an earlier link could make it climb out of the destination.
     */
    private static void checkSymlinkTarget(Entry e) throws TarFormatException {
        String t = e.linkName();
        if (t.startsWith("/")) {
            throw new TarFormatException("unsafe: absolute symlink target " + t + " for " + e.name());
        }
        int depth = e.name().split("/").length - 1; // directories between the link and the destination root
        boolean sawName = false;
        for (String seg : t.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (sawName) {
                    throw new TarFormatException("unsafe: symlink target " + t + " for " + e.name() + " has '..' after a name");
                }
                depth--;
                if (depth < 0) {
                    throw new TarFormatException("unsafe: symlink target " + t + " for " + e.name() + " escapes the destination");
                }
            } else {
                sawName = true;
            }
        }
    }

    private static void applyMetadata(Path p, Entry meta) throws IOException {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < BITS.length; i++) {
            if ((meta.mode() & (0400 >> i)) != 0) {
                perms.add(BITS[i]);
            }
        }
        try {
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system: permissions cannot be applied
        }
        if (meta.mtime() >= 0 && meta.mtime() <= MAX_MTIME) {
            Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(meta.mtime())));
        }
    }
}
