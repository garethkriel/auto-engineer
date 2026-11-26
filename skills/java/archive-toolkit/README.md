# archive-toolkit

- **Language:** Java 21 (standard library only; `java.util.zip.Deflater/Inflater` for compression, everything else written here)
- **Tags:** zip, tar, ustar, crc32, archive, central-directory, path-traversal, strict-parser, differential-testing
- **Source round:** practice/java/20261126T1056Z

Strict ZIP and POSIX-ustar TAR readers/writers sharing one entry model (`ae.arch`).

- `Names.validate` / `ArchiveEntry`: relative, normalised names only (no `..`, absolute, drive, control chars, NUL, backslash); trailing `/` = directory.
- `Archives.checkTree` (duplicates incl. `a` vs `a/`, file used as parent), `extractTo` (CREATE_NEW, stays in root), `snapshot`.
- `Crc32`: table-driven, unsigned `value()`.
- `ZipWriter.write(entries, STORED|DEFLATED|AUTO, comment)`: deterministic, UTF-8 flag, max 65534 entries (0xFFFF is the ZIP64 marker).
- `ZipReader.read(bytes[, maxEntrySize])`, `comment`: EOCD must end exactly at EOF (so a fake EOCD inside a comment is skipped), central directory ends exactly at EOCD, local/central agreement (data descriptors allowed), overlap and range checks, deflate must yield exactly the declared size and consume exactly the compressed bytes, CRC, size cap before allocation.
- `TarWriter.write` / `TarReader.read(bytes[, stripDotSlash])`: ustar prefix/name split, octal fields, checksum, zero-padding and end-marker checks.

Tests: published/derived byte layouts, JDK `ZipInputStream`/`ZipFile`/`ZipOutputStream`, Info-ZIP `zip`/`unzip` and GNU `tar` as differential oracles (skipped via `assumeTrue` if missing), crafted-defect tests each with a control, exhaustive truncation and bit-flip loops. `mutate.sh` in the practice round holds the 69-mutant list.
