# ipv4-toolkit

- **Description:** IPv4 building blocks over `byte[]`: RFC 1071 checksum with RFC 1624 incremental update, strict `Ipv4Packet` encode/decode, MTU `Fragmenter`, stateful `Reassembler` (first-arrival-wins overlaps, malformed datagram discarded wholesale, timeout from first arrival, oldest-entry eviction, stats), and UDP with the pseudo-header checksum.
- **Language:** java (standard library only)
- **Tags:** networking, checksum, fragmentation, reassembly, binary-codec, differential-oracle
- **Source round:** `practice/java/20261125T0357Z`
- **Tests:** `mvn -q -B test` (80 tests: literal hex fixtures, one-defect rejection controls, shuffled/duplicated round trips, per-step byte-coverage oracle).

Notes: an offset-0 packet with MF clear is a non-fragment and bypasses reassembly state; `Checksum.update` may return 0x0000 where a recompute gives 0xffff (same ones-complement value).
