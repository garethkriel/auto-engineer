# vfs-inode-model

- **Description:** In-memory POSIX-like filesystem (`ae.vfs.Vfs`): inodes (file/dir/symlink), hard links, symlinks with 40-hop loop limit, `.`/`..` semantics, owner/group/other permissions with root bypass, byte quota with atomic failure, rename matrix, deep-copy snapshots preserving link identity, and `fsck()` invariant checker.
- **Language:** java
- **Tags:** filesystem, inode, symlink, permissions, quota, snapshot, fsck, path-resolution, capstone
- **Source round:** `practice/java/20261118T2356Z`

Reuse notes: one `walk(path, followLast)` routine (a deque of pending components, so symlink targets are pushed in front) serves every operation. Tests include a seeded differential test against a path-map model; keep `fsck()` after every mutation.
