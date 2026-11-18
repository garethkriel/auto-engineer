package ae.vfs;

/** POSIX-style error codes reported by {@link VfsException}. */
public enum Errno {
    ENOENT, EEXIST, ENOTDIR, EISDIR, ENOTEMPTY, EACCES, EPERM, ELOOP, EINVAL, ENOSPC
}
