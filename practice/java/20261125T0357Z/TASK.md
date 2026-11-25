# Task: IPv4 packet toolkit (ae.net)

Level 20 capstone (small end-to-end system: codec + fragmentation + stateful reassembler + UDP checksum).
Assumptions are recorded here because nobody is available to ask.

## Overlap check
`ls benchmark/java`: j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank,
j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate,
j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog,
j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql. None is a packet/checksum/reassembly task.
j05-intervals/j16-ranges merge intervals; the reassembler tracks covered byte ranges as a small part, but the task is
protocol codec + stateful buffering with timeouts, not interval merging. `skills/java/INDEX.md` has no IPv4 skill
(`cidr-toolkit`, `dns-toolkit`, `udp-stop-and-wait` are different cores).

## Design (package `ae.net`, standard library only)
- `Checksum`: RFC 1071 ones-complement 16-bit sum. `of(byte[], off, len)` returns the checksum (complement of the folded
  sum; odd length padded with a zero byte). `update(oldChecksum, oldWord, newWord)` is the RFC 1624 incremental form
  `~(~HC + ~m + m')`.
- `Ipv4Packet` (immutable, value equality incl. array contents): tos, id, df, mf, fragOffset (units of 8 bytes, 0..8191),
  ttl, protocol, src, dst (ints), options (length multiple of 4, <= 40), payload.
  `encode()` computes IHL, total length and header checksum; total length must be <= 65535 else `IllegalArgumentException`.
  `decode(byte[])` strictly rejects (`PacketException`): shorter than 20, version != 4, IHL < 5, header longer than data,
  total length < header length or > data length (trailing bytes beyond total length are ignored: link-layer padding),
  bad header checksum, reserved flag bit set. `df && (mf || fragOffset != 0)` is rejected as inconsistent.
- `Fragmenter.fragment(packet, mtu)`: total length <= mtu -> the packet itself. Else DF -> `FragmentationNeededException`.
  Options are kept on the first fragment only (documented simplification). Each non-last fragment carries
  `floor((mtu - headerLen)/8)*8` bytes; mtu must allow >= 8 payload bytes (else IllegalArgumentException). If the input is
  itself a non-last fragment, every output fragment keeps `mf=true`; offsets add to the input offset.
- `Reassembler(timeoutMillis, maxDatagrams)`, `Optional<Ipv4Packet> accept(Ipv4Packet p, long nowMillis)`:
  - key = (src, dst, protocol, id). A packet with `!mf && fragOffset == 0` is returned unchanged and touches no state.
  - Before processing, entries whose age (now - first arrival) >= timeout are discarded (`stats().expired`).
  - Malformed fragment => the whole datagram entry is discarded and `stats().malformed` incremented, result empty:
    non-last payload length not a multiple of 8, empty payload, end offset > 65535 - 20, a last fragment whose end disagrees with
    a previously seen last fragment, or any fragment extending beyond a known last end.
  - Overlaps: bytes that arrived first win. Duplicates are harmless.
  - Complete when the last fragment was seen and bytes [0,end) are all covered (needs the offset-0 fragment, whose header
    fields (tos, ttl, options, df) form the result; result has mf=false, fragOffset=0, same id/src/dst/protocol).
    The entry is removed on completion; a later duplicate starts a fresh entry.
  - New key when `maxDatagrams` entries exist: the oldest entry (first arrival, ties by insertion) is evicted (`stats().evicted`).
- `Udp.encode(srcIp, dstIp, srcPort, dstPort, payload)`: header + pseudo-header checksum (a computed 0 is sent as 0xFFFF);
  `Udp.decode(srcIp, dstIp, bytes)` -> `Datagram(srcPort, dstPort, payload)`; length field must equal the byte count;
  checksum field 0 means "none" and is accepted, otherwise the sum over pseudo-header + segment must verify (`PacketException`).

## Success criteria
`mvn -q -B clean test` passes. Tests: literal hex fixtures (Wikipedia header 0xb861, RFC 1071 example 0x220d), strict
decode rejections each with a control that differs by one defect, fragmentation/reassembly round trips with shuffled and
duplicated fragments against the original, a byte-coverage oracle for overlaps, expiry/eviction/stat counters, UDP literals,
and a mutation pass.
