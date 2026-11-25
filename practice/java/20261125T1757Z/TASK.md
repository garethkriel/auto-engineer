# 20261125T1757Z — BitTorrent-style core: bencode, metainfo, rarest-first picker, simulated swarm

Level 20 (capstone), topic: small end-to-end system combining parsing, hashing and simulation.

## Overlap check
Benchmark directory names: j01-roman j02-wordfreq j03-lru j04-csv j05-intervals j06-lcs j07-expr j08-graph j09-bank j10-counter j11-streams j12-fileindex j13-ratelimit j14-json j15-topo j16-ranges j17-cache j18-diff j19-allocate j20-cron j21-sheet j22-regex j23-segtree j24-queue j25-bizhours j26-rwlock j27-primes j28-script j29-kvlog j30-miniserver j31-ranked j32-fsm j33-pricing j34-vclock j35-minisql. None is a bencode/torrent/piece-selection task (j14-json is a JSON parser; bencode is a different grammar with canonical-form rules and binary strings). Existing skills: merkle-sync, rsync-delta, kademlia-dht are different problems.

## Design (package `ae.torrent`)
- `Bencode`, sealed `BValue` = `BInt(long)`, `BStr(byte[])`, `BList`, `BDict` (keys unsigned-byte ordered).
  - `Bencode.encode(BValue)` is canonical. `Bencode.decode(byte[])` is **strict**: no leading zeros, no `i-0e`, no empty/`-`-only ints, long overflow rejected, string length must fit the remaining input (checked before allocation), dict keys must be strings in strictly ascending unsigned-byte order, trailing bytes rejected, nesting depth limit 64. All violations throw `BencodeException` (unchecked).
- `Torrent`: single-file metainfo. `Torrent.create(name, data, pieceLength)` hashes pieces with SHA-1; `toBytes()` = bencoded `{announce-less} info` dict wrapper `d4:infod...ee`; `Torrent.parse(bytes)` validates types, `piece length > 0`, `pieces` length multiple of 20, piece count == ceil(length / pieceLength), `length >= 0`, name non-empty. `infoHash()` = SHA-1 of the canonical bencoding of the info dict (extra info keys are preserved so they change the hash). `pieceSize(i)` (last piece short), `verifyPiece(i, bytes)`.
- `PiecePicker`: per-leecher view of a peer set. `addPeer(id, BitSet)` (replaces), `peerHave(id, piece)` (idempotent; unknown peer -> IAE), `removePeer(id)`, `availability(piece)` = number of known peers having it, `pick(BitSet candidates)` = candidate with the lowest availability, ties to the lowest index; -1 if none.
- `Swarm`: deterministic in-process simulation, full mesh. Peers: seeds (optional departure tick), poisoners (claim every piece, serve corrupted data) and leechers. Each tick, leechers in id order download up to `downloadSlots` pieces; each piece is chosen by the leecher's picker among pieces it lacks that some live, unbanned peer with a free upload slot (`uploadSlots` per tick) has; the uploader is the lowest id with a free slot. Data is hash-verified; a failure counts a strike against the uploader, 2 strikes ban that uploader for that leecher (picker `removePeer`). A successful piece is announced to every other leecher's picker immediately. `step()` returns false when nothing was attempted (stall); `run(maxTicks)` stops at completion or stall.

## Success criteria
- Literal bencode vectors (`i42e`, `4:spam`, `d3:cow3:moo4:spam4:eggse`) plus a rejection table where each bad input is paired with a control that differs only by the defect; random value round-trip and decode(garbage) never throws anything but `BencodeException`.
- SHA-1 of `abc` equals the published digest; torrent create/toBytes/parse round trip; info hash equals SHA-1 of the encoded info dict; each metainfo validation rule has its own rejecting test.
- Picker: rarest-first + tie-break tables, availability equals an oracle after random add/have/remove sequences.
- Swarm: honest swarms complete with byte-identical data, never exceed upload slots, never ban an honest peer, availability matches an oracle after every tick; a poisoner plus an honest seed still completes; poisoner-only swarm stalls and terminates; a departing seed that leaves too early causes a stall.
- `mvn -q -B clean test` passes.
