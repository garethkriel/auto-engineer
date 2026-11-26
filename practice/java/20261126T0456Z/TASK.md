# 20261126T0456Z — tar archive toolkit `ae.tar` (level 20 capstone)

## Task
Build, with the JDK only, a strict POSIX ustar/pax archive toolkit:
a value type `Entry`, a deterministic `TarWriter`, a strict `TarReader`, and a safe `TarExtractor`.
It must interoperate with the installed GNU tar 1.35 in both directions.

Overlap check: `benchmark/java/` names are j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank,
j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet,
j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm,
j33-pricing, j34-vclock, j35-minisql. None is an archive format. (`file-kv-store`, `ts-block-file`, `png-codec` are other binary formats; no tar skill exists.)

## Design (single package `ae.tar`)
- `Entry(name, type, mode, uid, gid, mtime, linkName, uname, gname, data)`: `Type` = FILE('0'), HARDLINK('1'), SYMLINK('2'), DIRECTORY('5').
  Names are safe relative paths: non-empty, no NUL, no leading/trailing `/`, no empty / `.` / `..` segments, valid Unicode.
  mode 0..07777, uid/gid >= 0, mtime any long (seconds). Only FILE has data; HARDLINK and SYMLINK need a non-empty linkName; others "".
  `data` is defensively copied; equality compares data by content.
- `TarWriter.write(List<Entry>)`: 512-byte blocks, ustar magic `ustar\0` + `00`, directory names get a trailing `/`, ends with two zero blocks (no 10240 padding).
  A field that does not fit the ustar header moves to a pax extended header (`x` entry, records `"<len> key=value\n"`, `len` counts itself):
  - name: ASCII and <= 100 bytes fits `name`; else split at a `/` into `prefix` (<=155) + `name` (<=100), rightmost split that fits; else pax `path`. Non-ASCII always pax.
  - linkName: ASCII <= 100 else pax `linkpath`. uname/gname: ASCII <= 31 bytes else pax. uid/gid > 0o7777777 and mtime outside 0..0o77777777777: pax.
  - the overridden header field holds a placeholder (truncated ASCII-safe text / 0). Record order: path, linkpath, uid, gid, uname, gname, mtime.
  devmajor/devminor are all NUL, like GNU tar.
- `TarReader.read(byte[])` throws `TarFormatException` (checked, extends IOException) for: length not a multiple of 512, bad checksum, bad magic/version,
  malformed or unterminated octal, mode > 07777, unknown typeflag, non-zero bytes after a NUL inside a text field, invalid UTF-8, size larger than the remaining
  bytes (checked before allocating), non-zero data padding, a non-zero directory/link size, `x` header followed by `x`/EOF/end-marker, malformed pax records (length must match exactly,
  no trailing bytes), unsafe or invalid entry names, a missing end marker (two zero blocks) and non-zero bytes after it. Accepts `g` global headers (ignored),
  pax keys path/linkpath/uid/gid/uname/gname/mtime (decimal, fraction floored)/size; unknown pax keys are ignored; later duplicate wins.
  A directory name loses exactly one trailing `/`. Pax header size is capped at 1 MiB.
- `TarExtractor.extract(List<Entry>, Path dest)`: validates everything first (so a rejected archive leaves `dest` untouched), then writes.
  Rejects: duplicate names, a name below a non-directory entry or below an archive symlink, symlink targets that are empty, absolute or lexically escape `dest`,
  hardlinks to a name that is not an earlier FILE/HARDLINK entry, and anything that meets a pre-existing symlink, non-directory ancestor or existing file on disk.
  Applies `mode & 0777` and mtime (directories last). setuid/setgid/sticky bits are dropped.

## Success criteria
- A hand-built header (independent Python arithmetic) equals the writer's block byte for byte; pax record length literals incl. the 99 -> 101 digit-count jump.
- Round trips: every entry kind, name-length boundaries (100 / 101 / prefix 155 / 256 / 257), non-ASCII, long link/user names, huge uid, negative/huge mtime, empty file, block-boundary sizes (0, 1, 511, 512, 513), seeded random batches.
- GNU tar differential: our archives list and extract identically with `tar`; `tar --format=ustar` and `--format=pax` archives of a real tree (deep paths, unicode, symlinks, empty files) read back as the tree; our header block equals GNU's for a plain entry.
- Strictness: one re-sealed crafted header per rule with a control differing only by that defect; truncation at every length; flip every byte (metadata: rejected or identical; data byte: only that byte changes); huge size field on a tiny archive is rejected without allocation.
- Extractor: traversal, absolute, symlink write-through (in archive and pre-existing on disk), escaping symlink targets, duplicate, hardlink rules, side-effect-free rejection.
- A mutation pass over the main code; survivors justified.

## Assumptions
Locale: tests pass `LC_ALL=C.UTF-8` and `--quoting-style=literal` to tar. TOCTOU between validation and writing is out of scope. GNU long-name (`L`/`K`) and base-256 numbers are not supported (rejected as unknown typeflag / bad octal).
