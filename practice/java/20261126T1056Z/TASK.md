# Task: strict ZIP and TAR (ustar) archive toolkit from scratch (package `ae.arch`)

Level 20 capstone. **Overlap check:** `benchmark/java/` names j01-roman ... j35-minisql were read; none is an archive format (closest: j12-fileindex = file indexing, j29-kvlog = key/value log; neither involves container formats). Existing skill `deflate-codec` covers DEFLATE bits; this round is the container layer (headers, central directory, checksums, path safety) and uses `java.util.zip.Deflater/Inflater` only for the compression itself. CRC-32 is written here.

Assumptions: names are UTF-8; no ZIP64, no encryption, no multi-disk, no tar extensions (pax, GNU long names, links, devices).

## API
- `ArchiveException` (unchecked): any malformed or unsafe archive/tree.
- `Names.validate(String)` throws `IllegalArgumentException`: non-empty, valid UTF-16 (encodable), no NUL or other control chars (< 0x20), no `\`, not starting with `/`, no `X:` drive prefix, every segment (after dropping ONE trailing `/`) non-empty and not `.` or `..`.
- `ArchiveEntry(String name, byte[] data)` record: validates the name, copies data on the way in and out; a name ending in `/` is a directory and must have empty data; value `equals`/`hashCode`.
- `Archives.checkTree(List<ArchiveEntry>)` throws `ArchiveException` for duplicates (`a` and `a/` collide) and for a file used as a parent directory (`a` file plus `a/b`). `Archives.extractTo(Path, List)` writes the tree (refuses to escape the root, refuses to overwrite). `Archives.snapshot(Path)` reads a directory back as a sorted entry list (dirs end with `/`, parents before children).
- `Crc32`: `update(byte[],int,int)`, `value()` (unsigned long), `static of(byte[])`.
- `ZipWriter.write(List<ArchiveEntry>, Method, String comment)`; `Method` = `STORED`, `DEFLATED`, `AUTO` (deflate only when strictly smaller); directories always stored. Writes UTF-8 flag (bit 11), DOS time 1980-01-01 00:00:00, max 65534 entries (0xFFFF in the count is the ZIP64 marker), comment <= 65535 UTF-8 bytes.
- `ZipReader.read(byte[])` / `read(byte[], long maxEntrySize)` (default 64 MiB) and `ZipReader.comment(byte[])`. Strict: EOCD must end exactly at end of file (comment length exact), central directory must end exactly at the EOCD, entry count must match, zip64 markers/encryption/unknown flag bits/unknown methods/version needed > 20 rejected, local header must agree with the central one (name, method; sizes and CRC unless bit 3 data descriptor), local+data ranges must not overlap, deflate stream must produce exactly the declared size and consume exactly the compressed bytes, CRC verified, names validated, tree checked, declared size capped BEFORE allocation.
- `TarWriter.write(List<ArchiveEntry>)` POSIX ustar: 512-byte headers, octal fields, checksum, names > 100 bytes split into prefix(<=155)/name(<=100) at the last usable `/`, otherwise `IllegalArgumentException`; ends with two zero blocks.
- `TarReader.read(byte[])` strict: length multiple of 512, checksum, magic `ustar\0` + version `00`, only typeflag `0`/NUL (file) and `5` (dir), octal fields digits only, padding bytes zero, truncated data rejected, after the first zero block everything must be zero and at least two zero blocks must exist, names validated, tree checked.

## Success criteria
- Published/derived literals: CRC-32("123456789") = 0xCBF43926, hand-derived byte layout (112-byte one-file ZIP), ustar header offsets.
- Differential: our ZIP read by JDK `ZipInputStream`/`ZipFile` and Info-ZIP `unzip -t`; JDK `ZipOutputStream` (with data descriptors) and Info-ZIP `zip` output read by us; our TAR read by GNU `tar`; GNU `tar --format=ustar` output read by us.
- Rejection tests, each with a control that differs only by the one defect (crc, size, duplicate, traversal, encrypted, method, zip64, comment length, overlap, bomb/size cap, bad UTF-8, dir with data, tar checksum/magic/typeflag/padding).
- Exhaustive truncation (every prefix must be rejected) and flip-every-byte loops ("exception or identical entries").
- A mutation script (`mutate.sh`) over the main sources where every mutant must be killed or justified.
