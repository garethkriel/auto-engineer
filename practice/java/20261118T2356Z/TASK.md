# Capstone: in-memory POSIX-like virtual filesystem (`ae.vfs`)

Level 20 capstone. Overlap check against `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr,
graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex,
segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql):
none is a filesystem / path-resolution task. Existing skills `glob-matcher`, `dir-snapshot-diff` work on real files, not an inode model.

## Design
- `Vfs(long quotaBytes)`: owns an inode tree. `root()` = superuser session; `login(uid, gid)` = ordinary user session.
  `snapshot()` / `restore(Snapshot)` (deep copy, hard-link identity preserved), `fsck()` (list of invariant violations,
  empty = healthy), `usedBytes()`.
- `Inode` types FILE, DIR, SYMLINK; uid/gid, 9 permission bits, `nlink`; dirs keep a sorted entry map and a parent pointer.
- Session operations (absolute paths only): `mkdir`, `mkdirs`, `write`, `append`, `read`, `list`, `stat`, `lstat`,
  `link` (hard), `symlink`, `readlink`, `unlink`, `rmdir`, `rename`, `chmod`, `chown`, `realpath`.
- Errors: `VfsException` carrying an `Errno` (ENOENT, EEXIST, ENOTDIR, EISDIR, ENOTEMPTY, EACCES, EPERM, ELOOP, EINVAL, ENOSPC).
- Path walk: handles `.`, `..` (root's parent is root), repeated `/`, symlinks anywhere (relative targets resolve against the
  link's directory, absolute from root), max 40 expansions then ELOOP, search (x) permission on each traversed dir.
- Permissions: owner/group/other triple; uid 0 bypasses. Creating/removing/renaming entries needs w+x on the parent dir.
- Quota counts bytes of distinct file inodes (hard links share); a failing write must leave state unchanged (ENOSPC).
- `rename`: file over file replaces; dir over empty dir ok; dir over non-empty ENOTEMPTY; file over dir EISDIR; dir over file ENOTDIR;
  moving a dir into its own subtree EINVAL; same inode is a no-op.

## Success criteria
`mvn -q -B clean test` passes: unit tests per operation + errno table, symlink/loop/.. semantics, permission matrix,
quota atomicity, snapshot independence, and a seeded randomized differential test against a naive path-map model with
`fsck()` after every step.
