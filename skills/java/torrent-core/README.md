# torrent-core

- **Description:** BitTorrent-style building blocks in `ae.torrent`: strict canonical bencode (`Bencode`, `BValue` sealed family), single-file metainfo with SHA-1 piece hashes and info hash (`Torrent`), rarest-first `PiecePicker`, and a deterministic full-mesh `Swarm` simulation with seeds that depart, partial peers and poisoners that are banned after two hash failures.
- **Language:** java
- **Tags:** bencode, torrent, sha1, rarest-first, simulation, strict-decoder, mutation-checked
- **Source round:** 20261125T1757Z (`practice/java/20261125T1757Z`)
- **Tests:** `mvn -q -B test` (42 tests).

Notes: decoder rejects every non-canonical form (leading zeros, `i-0e`, unsorted/duplicate keys, trailing data, depth > 64, string length beyond input checked before allocation). Dict keys sort as unsigned bytes. Swarm tick semantics are documented in the `Swarm` class doc.
