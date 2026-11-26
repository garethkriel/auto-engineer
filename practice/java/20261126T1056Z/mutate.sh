#!/bin/bash
# Mutation check: each line is file<TAB>from<TAB>to (literal, must match once). Prints KILLED/SURVIVED.
cd "$(dirname "$0")"
M=src/main/java/ae/arch
# restore the original file even if this script is killed mid-mutant
trap '[ -n "$cur" ] && cp /tmp/mut.bak "$M/$cur"' EXIT INT TERM
cur=""
while IFS=$'\t' read -r f from to; do
  cp "$M/$f" /tmp/mut.bak; cur="$f"
  python3 - "$M/$f" "$from" "$to" <<'PY' || { echo "NOMATCH $f $from"; continue; }
import sys
p,a,b=sys.argv[1:4]; s=open(p).read()
if s.count(a)!=1: sys.exit(1)
open(p,'w').write(s.replace(a,b))
PY
  if timeout 300 mvn -q -B clean test >/tmp/mut.out 2>&1; then echo "SURVIVED $f: $from -> $to"; else
    if grep -q "COMPILATION ERROR" /tmp/mut.out; then echo "COMPILEFAIL $f: $from"; else echo "KILLED $f: $from -> $to"; fi; fi
  cp /tmp/mut.bak "$M/$f"; cur=""
done <<'LIST'
Crc32.java	0xEDB88320 ^ (c >>> 1)	0xEDB88321 ^ (c >>> 1)
Crc32.java	off > b.length - len	off > b.length - len + 1
Names.java	name.charAt(0) == '/'	name.charAt(0) == 'x'
Names.java	name.charAt(1) == ':'	name.charAt(1) == ';'
Names.java	c < 0x20 || c == 0x7f	c < 0x20
Names.java	seg.isEmpty() || seg.equals(".") || seg.equals("..")	seg.isEmpty() || seg.equals("..")
Names.java	seg.isEmpty() || seg.equals(".") || seg.equals("..")	seg.isEmpty() || seg.equals(".")
Names.java	body.split("/", -1)	body.split("/")
ArchiveEntry.java	name.endsWith("/") && data.length > 0	name.endsWith("/") && data.length > 1
ArchiveEntry.java	data = data.clone();	
Archives.java	isDir.put(stripSlash(e.name()), e.directory()) != null	isDir.put(e.name(), e.directory()) != null
Archives.java	parentIsDir != null && !parentIsDir	parentIsDir != null && parentIsDir
Archives.java	!target.startsWith(base) || target.equals(base)	!target.startsWith(base)
Archives.java	StandardOpenOption.CREATE_NEW	StandardOpenOption.CREATE
ZipWriter.java	def.length < data.length	def.length <= data.length
ZipWriter.java	method == Method.DEFLATED ||	method != Method.DEFLATED ||
ZipWriter.java	!e.directory() && method != Method.STORED	method != Method.STORED
ZipWriter.java	MAX_ENTRIES = 0xFFFE	MAX_ENTRIES = 0xFFFF
ZipWriter.java	cm.length > 0xFFFF	cm.length > 0xFFFE
ZipWriter.java	.putInt(entries.get(i).directory() ? 0x10 : 0)	.putInt(0)
ZipWriter.java	(0 << 9) | (1 << 5) | 1	(0 << 9) | (1 << 5) | 2
ZipWriter.java	return method == 8 ? 20 : 10;	return 20;
ZipReader.java	i + ZipWriter.EOCD_SIZE + u16(z, i + 20) == z.length	i + ZipWriter.EOCD_SIZE + u16(z, i + 20) <= z.length
ZipReader.java	if (u16(zip, e.pos + 8) != e.entries) {	if (false) {
ZipReader.java	e.entries == 0xFFFF || e.cdSize == 0xFFFFFFFFL || e.cdOffset == 0xFFFFFFFFL	e.cdSize == 0xFFFFFFFFL || e.cdOffset == 0xFFFFFFFFL
ZipReader.java	e.cdOffset + e.cdSize != e.pos	e.cdOffset + e.cdSize > e.pos
ZipReader.java	if (u16(zip, e.pos + 4) != 0 || u16(zip, e.pos + 6) != 0) {	if (u16(zip, e.pos + 4) != 0) {
ZipReader.java	if (p != end) {	if (p > end) {
ZipReader.java	if ((flags & FLAG_ENCRYPTED) != 0) {	if (false) {
ZipReader.java	if ((flags & ~ALLOWED_FLAGS) != 0) {	if (false) {
ZipReader.java	if (versionNeeded > 20) {	if (false) {
ZipReader.java	if (disk != 0) {	if (false) {
ZipReader.java	if (method != 0 && method != 8) {	if (method != 0) {
ZipReader.java	csize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || off == 0xFFFFFFFFL	csize == 0xFFFFFFFFL || off == 0xFFFFFFFFL
ZipReader.java	if (method == 0 && csize != size) {	if (false) {
ZipReader.java	if (next > end) {	if (next > end + 1) {
ZipReader.java	if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {	if (ranges.get(i)[0] < ranges.get(i - 1)[1] - 1) {
ZipReader.java	if (u16(z, o + 8) != c.method) {	if (false) {
ZipReader.java	if ((c.flags & FLAG_DESCRIPTOR) == 0	if (true
ZipReader.java	if (end > cdOffset) {	if (end > cdOffset + 1) {
ZipReader.java	if (c.size > maxEntrySize) {	if (c.size > maxEntrySize + 1) {
ZipReader.java	if (Crc32.of(data) != c.crc) {	if (false) {
ZipReader.java	if (inf.inflate(new byte[1]) != 0) {	if (false) {
ZipReader.java	if (!inf.finished()) {	if (false) {
ZipReader.java	if (inf.getRemaining() != 0) {	if (false) {
ZipReader.java	if (n != size) {	if (n > size) {
TarWriter.java	n.length - i - 1 <= NAME_LEN	n.length - i - 1 <= NAME_LEN + 1
TarWriter.java	Math.min(PREFIX_LEN, n.length - 2)	Math.min(PREFIX_LEN + 1, n.length - 2)
TarWriter.java	Math.min(PREFIX_LEN, n.length - 2)	Math.min(PREFIX_LEN, n.length - 1)
TarWriter.java	dir ? 0755 : 0644	dir ? 0644 : 0755
TarWriter.java	(byte) (dir ? '5' : '0')	(byte) '0'
TarWriter.java	new byte[(BLOCK - data.length % BLOCK) % BLOCK]	new byte[BLOCK - data.length % BLOCK]
TarWriter.java	out.writeBytes(new byte[2 * BLOCK]);	out.writeBytes(new byte[BLOCK]);
TarWriter.java	(h[i] & 0xFF);	(h[i]);
TarReader.java	if (tar.length % B != 0) {	if (false) {
TarReader.java	if (tar.length - p < 2 * B) {	if (tar.length - p < B) {
TarReader.java	if (!isZero(tar, p, tar.length - p)) {	if (false) {
TarReader.java	if (stored != TarWriter.checksum(h)) {	if (false) {
TarReader.java	TarWriter.MAGIC_OFF + 8, ("ustar\0" + "00")	TarWriter.MAGIC_OFF + 6, ("ustar\0" + "00")
TarReader.java	if (type == '0' || type == 0) {	if (type == '0') {
TarReader.java	octal(h, TarWriter.MODE_OFF, 8, "mode");	
TarReader.java	if (!isZero(h, TarWriter.LINK_OFF, 100)) {	if (false) {
TarReader.java	if (size != 0) {	if (false) {
TarReader.java	} else if (full.endsWith("/")) {	} else if (false) {
TarReader.java	if (!isZero(t, end, next - end)) {	if (false) {
TarReader.java	if (!isZero(h, off + n, len - n)) {	if (false) {
TarReader.java	if (i == off || i == off + len) {	if (i == off) {
TarReader.java	if (h[j] != 0 && h[j] != ' ') {	if (false) {
TarReader.java	full = prefix.isEmpty() ? name : prefix + "/" + name;	full = name;
LIST
