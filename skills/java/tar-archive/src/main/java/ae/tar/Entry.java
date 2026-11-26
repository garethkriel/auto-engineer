package ae.tar;

import java.util.Arrays;
import java.util.Objects;

/**
 * One archive member. Names are safe relative paths (no leading or trailing slash, no empty, {@code .} or
 * {@code ..} segment), so an {@code Entry} can never describe a path traversal by name alone.
 *
 * @param name     slash-separated relative path, without a trailing slash even for directories
 * @param type     entry kind
 * @param mode     permission bits, 0..07777
 * @param uid      numeric owner, non-negative
 * @param gid      numeric group, non-negative
 * @param mtime    modification time in seconds since the epoch
 * @param linkName target of a symlink (free text) or of a hard link (an archive name); empty otherwise
 * @param uname    owner name, may be empty
 * @param gname    group name, may be empty
 * @param data     file content (only FILE entries have any)
 */
public record Entry(String name, Type type, int mode, long uid, long gid, long mtime,
                    String linkName, String uname, String gname, byte[] data) {

    /** Entry kinds with their ustar typeflag characters. */
    public enum Type {
        FILE('0'), HARDLINK('1'), SYMLINK('2'), DIRECTORY('5');

        final char flag;

        Type(char flag) {
            this.flag = flag;
        }
    }

    /** Validates every field and copies {@code data}. */
    public Entry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        checkName(name, "name");
        if (mode < 0 || mode > 07777) {
            throw new IllegalArgumentException("mode out of range 0..07777: " + mode);
        }
        if (uid < 0 || gid < 0) {
            throw new IllegalArgumentException("uid and gid must be non-negative");
        }
        checkText(linkName, "linkName");
        checkText(uname, "uname");
        checkText(gname, "gname");
        switch (type) {
            case SYMLINK -> {
                if (linkName.isEmpty()) {
                    throw new IllegalArgumentException("linkName must not be empty for SYMLINK");
                }
            }
            case HARDLINK -> {
                if (linkName.isEmpty()) {
                    throw new IllegalArgumentException("linkName must not be empty for HARDLINK");
                }
                checkName(linkName, "linkName");
            }
            default -> {
                if (!linkName.isEmpty()) {
                    throw new IllegalArgumentException("linkName must be empty for " + type);
                }
            }
        }
        if (type != Type.FILE && data.length != 0) {
            throw new IllegalArgumentException("only FILE entries carry data, not " + type);
        }
        data = data.clone();
    }

    /** A regular file owned by 0/0. */
    public static Entry file(String name, int mode, long mtime, byte[] data) {
        return new Entry(name, Type.FILE, mode, 0, 0, mtime, "", "", "", data);
    }

    /** A directory owned by 0/0. */
    public static Entry dir(String name, int mode, long mtime) {
        return new Entry(name, Type.DIRECTORY, mode, 0, 0, mtime, "", "", "", new byte[0]);
    }

    /** A symbolic link (mode 0777, mtime 0). */
    public static Entry symlink(String name, String target) {
        return new Entry(name, Type.SYMLINK, 0777, 0, 0, 0, target, "", "", new byte[0]);
    }

    /** A hard link to an earlier entry named {@code target} (mode 0644, mtime 0; extraction copies the target's). */
    public static Entry hardlink(String name, String target) {
        return new Entry(name, Type.HARDLINK, 0644, 0, 0, 0, target, "", "", new byte[0]);
    }

    /** A copy of the content. */
    @Override
    public byte[] data() {
        return data.clone();
    }

    static void checkName(String n, String what) {
        Objects.requireNonNull(n, what);
        Text.utf8(n, what);
        if (n.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " contains NUL");
        }
        for (String seg : n.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException(what + " is not a safe relative path: " + n.replace("\0", "<NUL>"));
            }
        }
    }

    private static void checkText(String s, String what) {
        Objects.requireNonNull(s, what);
        Text.utf8(s, what);
        if (s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " contains NUL");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Entry e && name.equals(e.name) && type == e.type && mode == e.mode && uid == e.uid
            && gid == e.gid && mtime == e.mtime && linkName.equals(e.linkName) && uname.equals(e.uname)
            && gname.equals(e.gname) && Arrays.equals(data, e.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, mode, uid, gid, mtime, linkName, uname, gname) * 31 + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Entry[" + type + " " + name + " mode=" + Integer.toOctalString(mode) + " uid=" + uid + " gid=" + gid
            + " mtime=" + mtime + (linkName.isEmpty() ? "" : " -> " + linkName) + " uname=" + uname + " gname=" + gname
            + " size=" + data.length + "]";
    }
}
