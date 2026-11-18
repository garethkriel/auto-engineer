package ae.vfs;

/** Immutable metadata snapshot of an inode. */
public record Stat(long inode, FileType type, long size, int uid, int gid, int mode, int nlink) {
}
