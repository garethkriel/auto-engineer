package ae.vfs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * In-memory POSIX-like filesystem: inodes, directories, hard links, symlinks, permissions, a byte quota,
 * snapshots and an invariant checker. All access goes through a {@link Session} bound to a user.
 * Not thread-safe.
 */
public final class Vfs {
    /** Maximum number of symlink expansions during one path walk. */
    public static final int MAX_SYMLINK_HOPS = 40;
    private static final byte[] EMPTY = new byte[0];

    private final long quota;
    private Inode root;
    private long used;
    private long nextId = 1;

    public Vfs(long quotaBytes) {
        if (quotaBytes < 0) {
            throw new IllegalArgumentException("quota must be >= 0");
        }
        this.quota = quotaBytes;
        this.root = newInode(FileType.DIR, 0, 0, 0755);
        this.root.parent = this.root;
        this.root.nlink = 1;
    }

    /** Superuser session (uid 0, bypasses permission checks). */
    public Session root() {
        return new Session(0, 0);
    }

    /** Session for an ordinary (or root, if uid is 0) user. */
    public Session login(int uid, int gid) {
        return new Session(uid, gid);
    }

    /** Bytes held by distinct file inodes. */
    public long usedBytes() {
        return used;
    }

    /** Opaque deep copy of the whole tree. */
    public static final class Snapshot {
        private final Inode root;
        private final long used;

        private Snapshot(Inode root, long used) {
            this.root = root;
            this.used = used;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(copyTree(root), used);
    }

    /** Replace the live tree with a fresh copy of the snapshot; the snapshot itself stays reusable. */
    public void restore(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        this.root = copyTree(s.root);
        this.used = s.used;
    }

    private Inode copyTree(Inode src) {
        Map<Inode, Inode> map = new IdentityHashMap<>();
        Inode copy = copyInode(src, map);
        copy.parent = copy;
        return copy;
    }

    private Inode copyInode(Inode src, Map<Inode, Inode> map) {
        Inode seen = map.get(src);
        if (seen != null) {
            return seen;
        }
        Inode c = new Inode(src.id, src.type);
        map.put(src, c);
        c.uid = src.uid;
        c.gid = src.gid;
        c.mode = src.mode;
        c.nlink = src.nlink;
        c.data = src.data; // arrays are never mutated in place, only replaced
        c.target = src.target;
        for (Map.Entry<String, Inode> e : src.entries.entrySet()) {
            Inode child = copyInode(e.getValue(), map);
            if (child.type == FileType.DIR) {
                child.parent = c;
            }
            c.entries.put(e.getKey(), child);
        }
        return c;
    }

    /** Check structural invariants; returns human-readable violations (empty when healthy). */
    public List<String> fsck() {
        List<String> problems = new ArrayList<>();
        Map<Inode, Integer> refs = new IdentityHashMap<>();
        Map<Inode, Boolean> visited = new IdentityHashMap<>();
        walkCheck(root, "/", refs, visited, problems);
        long sum = 0;
        for (Map.Entry<Inode, Integer> e : refs.entrySet()) {
            Inode n = e.getKey();
            if (n != root && n.nlink != e.getValue()) {
                problems.add("inode " + n.id + " nlink " + n.nlink + " but " + e.getValue() + " references");
            }
            if (n.type == FileType.FILE) {
                sum += n.data.length;
            }
        }
        if (root.parent != root) {
            problems.add("root parent is not root");
        }
        if (sum != used) {
            problems.add("used " + used + " but files hold " + sum);
        }
        if (used > quota) {
            problems.add("used " + used + " exceeds quota " + quota);
        }
        return problems;
    }

    private void walkCheck(Inode dir, String path, Map<Inode, Integer> refs, Map<Inode, Boolean> visited,
            List<String> problems) {
        if (visited.put(dir, Boolean.TRUE) != null) {
            problems.add("directory reachable twice: " + path);
            return;
        }
        for (Map.Entry<String, Inode> e : dir.entries.entrySet()) {
            String name = e.getKey();
            Inode child = e.getValue();
            String cp = path.equals("/") ? "/" + name : path + "/" + name;
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                problems.add("bad entry name at " + cp);
            }
            refs.merge(child, 1, Integer::sum);
            if (child.type == FileType.DIR) {
                if (child.parent != dir) {
                    problems.add("wrong parent pointer at " + cp);
                }
                walkCheck(child, cp, refs, visited, problems);
            } else if (child.type == FileType.SYMLINK && child.target == null) {
                problems.add("symlink without target at " + cp);
            }
        }
    }

    private Inode newInode(FileType type, int uid, int gid, int mode) {
        Inode n = new Inode(nextId++, type);
        n.uid = uid;
        n.gid = gid;
        n.mode = mode;
        return n;
    }

    // ------------------------------------------------------------------------------------------------

    private static final class Inode {
        final long id;
        final FileType type;
        int uid;
        int gid;
        int mode;
        int nlink;
        byte[] data = EMPTY;
        String target;
        Inode parent; // directories only
        final TreeMap<String, Inode> entries = new TreeMap<>();

        Inode(long id, FileType type) {
            this.id = id;
            this.type = type;
        }
    }

    /** Result of a path walk: the directory holding the last component, its name and the inode (maybe null). */
    private record Res(Inode parent, String name, Inode node, String path) {
    }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        for (String p : path.split("/")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Operations performed as one user. */
    public final class Session {
        private final int uid;
        private final int gid;

        private Session(int uid, int gid) {
            this.uid = uid;
            this.gid = gid;
        }

        // ---- permission helpers -----------------------------------------------------------------

        private boolean allowed(Inode n, int bit) {
            if (uid == 0) {
                return true;
            }
            int shift = n.uid == uid ? 6 : n.gid == gid ? 3 : 0;
            return ((n.mode >> shift) & bit) != 0;
        }

        private void need(Inode n, int bit, String what) {
            if (!allowed(n, bit)) {
                throw new VfsException(Errno.EACCES, "permission denied: " + what);
            }
        }

        private void needDirWrite(Inode dir, String what) {
            need(dir, 02, what);
            need(dir, 01, what);
        }

        // ---- path walk --------------------------------------------------------------------------

        private Res walk(String path, boolean followLast) {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) {
                throw new VfsException(Errno.EINVAL, "path must be absolute: " + path);
            }
            ArrayDeque<String> todo = new ArrayDeque<>(split(path));
            ArrayList<Inode> stack = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            stack.add(root);
            int hops = 0;
            while (!todo.isEmpty()) {
                String c = todo.pollFirst();
                Inode dir = stack.get(stack.size() - 1);
                if (c.equals(".")) {
                    continue;
                }
                if (c.equals("..")) {
                    if (stack.size() > 1) {
                        stack.remove(stack.size() - 1);
                        names.remove(names.size() - 1);
                    }
                    continue;
                }
                need(dir, 01, "search " + c);
                Inode child = dir.entries.get(c);
                boolean last = todo.isEmpty();
                if (child != null && child.type == FileType.SYMLINK && (!last || followLast)) {
                    if (++hops > MAX_SYMLINK_HOPS) {
                        throw new VfsException(Errno.ELOOP, "too many symbolic links: " + path);
                    }
                    List<String> parts = split(child.target);
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        todo.addFirst(parts.get(i));
                    }
                    if (child.target.startsWith("/")) {
                        stack.subList(1, stack.size()).clear();
                        names.clear();
                    }
                    continue;
                }
                if (last) {
                    return new Res(dir, c, child, join(names) + "/" + c);
                }
                if (child == null) {
                    throw new VfsException(Errno.ENOENT, "no such directory: " + c);
                }
                if (child.type != FileType.DIR) {
                    throw new VfsException(Errno.ENOTDIR, "not a directory: " + c);
                }
                stack.add(child);
                names.add(c);
            }
            Inode top = stack.get(stack.size() - 1);
            Inode par = stack.size() > 1 ? stack.get(stack.size() - 2) : null;
            return new Res(par, null, top, names.isEmpty() ? "/" : join(names));
        }

        private String join(List<String> names) {
            return names.isEmpty() ? "" : "/" + String.join("/", names);
        }

        private Res named(String path, boolean followLast, String what) {
            Res r = walk(path, followLast);
            if (r.name == null) {
                throw new VfsException(Errno.EINVAL, what + " on a directory root or dot path: " + path);
            }
            return r;
        }

        private Inode existing(String path, boolean follow) {
            Res r = walk(path, follow);
            if (r.node == null) {
                throw new VfsException(Errno.ENOENT, "no such file: " + path);
            }
            return r.node;
        }

        // ---- operations -------------------------------------------------------------------------

        public void mkdir(String path) {
            Res r = walk(path, false);
            if (r.node != null) {
                throw new VfsException(Errno.EEXIST, "exists: " + path);
            }
            needDirWrite(r.parent, "mkdir " + path);
            Inode d = newInode(FileType.DIR, uid, gid, 0755);
            d.parent = r.parent;
            d.nlink = 1;
            r.parent.entries.put(r.name, d);
        }

        /** mkdir -p: creates missing directories, accepts existing ones, rejects a file in the chain. */
        public void mkdirs(String path) {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) {
                throw new VfsException(Errno.EINVAL, "path must be absolute: " + path);
            }
            StringBuilder cur = new StringBuilder();
            for (String part : split(path)) {
                cur.append('/').append(part);
                Res r = walk(cur.toString(), true);
                if (r.node == null) {
                    mkdir(cur.toString());
                } else if (r.node.type != FileType.DIR) {
                    throw new VfsException(Errno.ENOTDIR, "not a directory: " + cur);
                }
            }
        }

        public void write(String path, byte[] content) {
            Objects.requireNonNull(content, "content");
            Res r = walk(path, true);
            storeFile(r, path, content.clone(), false);
        }

        public void append(String path, byte[] extra) {
            Objects.requireNonNull(extra, "extra");
            Res r = walk(path, true);
            storeFile(r, path, extra.clone(), true);
        }

        private void storeFile(Res r, String path, byte[] bytes, boolean append) {
            if (r.node != null && r.node.type == FileType.DIR) {
                throw new VfsException(Errno.EISDIR, "is a directory: " + path);
            }
            if (r.name == null) {
                throw new VfsException(Errno.EINVAL, "bad path: " + path);
            }
            Inode n = r.node;
            if (n == null) {
                needDirWrite(r.parent, "create " + path);
            } else {
                need(n, 02, "write " + path);
            }
            byte[] next = bytes;
            if (append && n != null) {
                next = Arrays.copyOf(n.data, n.data.length + bytes.length);
                System.arraycopy(bytes, 0, next, n.data.length, bytes.length);
            }
            long old = n == null ? 0 : n.data.length;
            long newUsed = Math.addExact(used - old, next.length);
            if (newUsed > quota) {
                throw new VfsException(Errno.ENOSPC, "quota exceeded writing " + path);
            }
            if (n == null) {
                n = newInode(FileType.FILE, uid, gid, 0644);
                n.nlink = 1;
                r.parent.entries.put(r.name, n);
            }
            n.data = next;
            used = newUsed;
        }

        public byte[] read(String path) {
            Inode n = existing(path, true);
            if (n.type == FileType.DIR) {
                throw new VfsException(Errno.EISDIR, "is a directory: " + path);
            }
            need(n, 04, "read " + path);
            return n.data.clone();
        }

        public List<String> list(String path) {
            Inode n = existing(path, true);
            if (n.type != FileType.DIR) {
                throw new VfsException(Errno.ENOTDIR, "not a directory: " + path);
            }
            need(n, 04, "list " + path);
            return Collections.unmodifiableList(new ArrayList<>(n.entries.keySet()));
        }

        public Stat stat(String path) {
            return statOf(existing(path, true));
        }

        public Stat lstat(String path) {
            return statOf(existing(path, false));
        }

        private Stat statOf(Inode n) {
            long size = n.type == FileType.FILE ? n.data.length : n.type == FileType.SYMLINK ? n.target.length() : 0;
            return new Stat(n.id, n.type, size, n.uid, n.gid, n.mode, n.nlink);
        }

        /** Hard link; does not follow a symlink in {@code existing}; directories cannot be linked. */
        public void link(String existing, String newPath) {
            Inode n = existing(existing, false);
            if (n.type == FileType.DIR) {
                throw new VfsException(Errno.EPERM, "hard link to directory: " + existing);
            }
            Res r = named(newPath, false, "link");
            if (r.node != null) {
                throw new VfsException(Errno.EEXIST, "exists: " + newPath);
            }
            needDirWrite(r.parent, "link " + newPath);
            r.parent.entries.put(r.name, n);
            n.nlink++;
        }

        public void symlink(String target, String path) {
            Objects.requireNonNull(target, "target");
            if (target.isEmpty()) {
                throw new VfsException(Errno.EINVAL, "empty symlink target");
            }
            Res r = named(path, false, "symlink");
            if (r.node != null) {
                throw new VfsException(Errno.EEXIST, "exists: " + path);
            }
            needDirWrite(r.parent, "symlink " + path);
            Inode n = newInode(FileType.SYMLINK, uid, gid, 0777);
            n.target = target;
            n.nlink = 1;
            r.parent.entries.put(r.name, n);
        }

        public String readlink(String path) {
            Inode n = existing(path, false);
            if (n.type != FileType.SYMLINK) {
                throw new VfsException(Errno.EINVAL, "not a symlink: " + path);
            }
            return n.target;
        }

        public void unlink(String path) {
            Res r = named(path, false, "unlink");
            if (r.node == null) {
                throw new VfsException(Errno.ENOENT, "no such file: " + path);
            }
            if (r.node.type == FileType.DIR) {
                throw new VfsException(Errno.EISDIR, "is a directory: " + path);
            }
            needDirWrite(r.parent, "unlink " + path);
            r.parent.entries.remove(r.name);
            if (--r.node.nlink == 0 && r.node.type == FileType.FILE) {
                used -= r.node.data.length;
            }
        }

        public void rmdir(String path) {
            Res r = walk(path, false);
            if (r.node == null) {
                throw new VfsException(Errno.ENOENT, "no such directory: " + path);
            }
            if (r.node.type != FileType.DIR) {
                throw new VfsException(Errno.ENOTDIR, "not a directory: " + path);
            }
            if (r.node == root || r.name == null) {
                throw new VfsException(Errno.EINVAL, "cannot remove: " + path);
            }
            if (!r.node.entries.isEmpty()) {
                throw new VfsException(Errno.ENOTEMPTY, "directory not empty: " + path);
            }
            needDirWrite(r.parent, "rmdir " + path);
            r.parent.entries.remove(r.name);
            r.node.nlink = 0;
        }

        public void rename(String from, String to) {
            Res src = named(from, false, "rename");
            Res dst = named(to, false, "rename");
            if (src.node == null) {
                throw new VfsException(Errno.ENOENT, "no such file: " + from);
            }
            if (src.node == dst.node) {
                return; // same inode (including identical path): nothing to do
            }
            Inode s = src.node;
            if (s.type == FileType.DIR) {
                for (Inode a = dst.parent; ; a = a.parent) {
                    if (a == s) {
                        throw new VfsException(Errno.EINVAL, "cannot move a directory into itself: " + from);
                    }
                    if (a == root) {
                        break;
                    }
                }
            }
            Inode d = dst.node;
            if (d != null) {
                if (s.type == FileType.DIR && d.type != FileType.DIR) {
                    throw new VfsException(Errno.ENOTDIR, "destination is not a directory: " + to);
                }
                if (s.type != FileType.DIR && d.type == FileType.DIR) {
                    throw new VfsException(Errno.EISDIR, "destination is a directory: " + to);
                }
                if (d.type == FileType.DIR && !d.entries.isEmpty()) {
                    throw new VfsException(Errno.ENOTEMPTY, "destination not empty: " + to);
                }
            }
            needDirWrite(src.parent, "rename " + from);
            needDirWrite(dst.parent, "rename " + to);
            if (d != null) {
                dst.parent.entries.remove(dst.name);
                if (--d.nlink == 0 && d.type == FileType.FILE) {
                    used -= d.data.length;
                }
            }
            src.parent.entries.remove(src.name);
            dst.parent.entries.put(dst.name, s);
            if (s.type == FileType.DIR) {
                s.parent = dst.parent;
            }
        }

        public void chmod(String path, int mode) {
            if ((mode & ~0777) != 0) {
                throw new VfsException(Errno.EINVAL, "mode out of range: " + Integer.toOctalString(mode));
            }
            Inode n = existing(path, true);
            if (uid != 0 && n.uid != uid) {
                throw new VfsException(Errno.EPERM, "not owner: " + path);
            }
            n.mode = mode;
        }

        public void chown(String path, int newUid, int newGid) {
            Inode n = existing(path, true);
            if (uid != 0) {
                throw new VfsException(Errno.EPERM, "only root may chown: " + path);
            }
            n.uid = newUid;
            n.gid = newGid;
        }

        /** Canonical absolute path with every symlink, "." and ".." resolved; the target must exist. */
        public String realpath(String path) {
            Res r = walk(path, true);
            if (r.node == null) {
                throw new VfsException(Errno.ENOENT, "no such file: " + path);
            }
            return r.path;
        }
    }
}
