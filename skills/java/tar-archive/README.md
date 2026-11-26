# tar-archive

- **Language:** Java 21, standard library only.
- **Tags:** tar, ustar, pax, archive, binary-format, strict-decoder, path-traversal, symlink-safety, gnu-tar-differential
- **Source round:** `practice/java/20261126T0456Z`

POSIX ustar/pax archive toolkit in package `ae.tar`:

- `Entry` (record): FILE / HARDLINK / SYMLINK / DIRECTORY with safe relative names (no `..`, empty or absolute segments), validated fields, content-based equality.
- `TarWriter.write(List<Entry>)`: deterministic ustar; prefix/name split at the rightmost fitting `/`; anything that does not fit (long or non-ASCII names, long link/user names, ids > 0o7777777, mtime outside 0..0o77777777777) goes to a pax `x` header (`Pax.record` length counts its own digits, e.g. body 98 -> `101`).
- `TarReader.read(byte[])`: strict; checksum (unsigned), magic/version, strict octal, NUL-clean text fields, strict UTF-8, zero padding/devices/header tail, size checked against remaining bytes before allocating, pax rules (sequencing, 1 MiB cap, floor of fractional mtime), end marker of two zero blocks, nothing after it. Throws `TarFormatException`.
- `TarExtractor.extract(entries, dest)`: validates everything first (duplicates, entries below non-directories or archive symlinks, symlink targets must be relative with `..` only as a leading run within the link's depth, hard links to earlier files only, pre-existing symlinks/files on disk never followed or overwritten), then writes; directory modes and times are applied last, innermost first.

Tests (141): literal header bytes, boundary tables for every ustar field, seeded random round trips, GNU tar as oracle in both directions (listing, extraction trees, `--format=ustar|pax` archives incl. non-ASCII names via a UTF-8 shell script), crafted re-sealed headers with controls, truncation at every length, flip-every-byte, extractor traversal and side-effect-free rejection. GNU tests skip when `tar` is not GNU tar. Mutation-checked (65 mutants; survivors: equivalent splitPoint `i >= 1`, redundant `size > avail` guard given length % 512, directory metadata order that only root cannot observe).

Pitfalls recorded: GNU tar rejects pax uid/gid above 32 bits; GNU restores a directory's mtime as soon as it meets a member outside it, so oracle archives must keep children contiguous; JVM file names are ASCII under the POSIX locale, so on-disk non-ASCII names need `sun.jnu.encoding` support (use a shell script for them).
