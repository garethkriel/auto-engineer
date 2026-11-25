# Task: `ae.proto` — Protocol-Buffers-style wire codec with a schema language (level 20 capstone)

Overlap check: read the 35 `benchmark/java` directory names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is a binary wire-format / schema-driven codec. (`j14-json` is a text parser; this is a varint/tag-length binary format.) Existing skills `columnar-store`, `dns-toolkit` have binary codecs but no varints, schema language or unknown-field preservation.

## Assumptions
- Standard library only, Java 21. Wire format is the public protobuf encoding (varint, fixed32/64, length-delimited). Groups (wire types 3/4) are rejected.
- Schema language is a proto3-like subset parsed from text; no `import`, `enum`, `oneof`, `map`.

## Design
- `Varint`: unsigned LEB128 over `long` (`encode`, `read`, `size`), `zigzag32/64` and inverses. A varint is 1–10 bytes; a 10th byte > 1 is an overflow error; truncation is an error. Non-canonical (padded) varints are accepted on read.
- `Kind` enum: INT32 INT64 UINT32 UINT64 SINT32 SINT64 BOOL FIXED32 FIXED64 SFIXED32 SFIXED64 FLOAT DOUBLE STRING BYTES MESSAGE. Java value types: INT32/SINT32/SFIXED32 `Integer`; INT64/SINT64/SFIXED64/UINT64/FIXED64 `Long` (unsigned ones hold the bit pattern); UINT32/FIXED32 `Long` in 0..2^32-1; BOOL `Boolean`; FLOAT `Float`; DOUBLE `Double`; STRING `String` (must be well-formed UTF-16); BYTES `Bytes`; MESSAGE `Msg`.
- `Schema.parse(text)`: `message Name { [repeated] type name = N; ... }`, `//` comments. Message-typed fields may refer to later or recursive messages. Errors (`SchemaException`, message starts with `line L:`): duplicate message, duplicate field name/number, unknown type, number outside 1..536870911 or in 19000..19999, syntax errors.
- `Msg`: immutable value (type, field values keyed by number, preserved unknown fields). Builder-style `set(name, v)` (singular, replaces), `add(name, v)` (repeated), each returns a new `Msg`; values are type/range checked (`IllegalArgumentException`). Explicit presence: a set field is always encoded, even with default value. `equals`/`hashCode` structural (unknown fields included).
- `Codec.encode(Msg)`: fields in ascending number order, then unknown fields in the order they were read. Repeated numeric/bool scalars are packed (one length-delimited field) when non-empty; strings/bytes/messages are one field per element. `Codec.decode(MessageType, byte[])`: singular fields last-wins (message-typed singular fields merge is NOT done: last wins), repeated fields append (packed or unpacked accepted for numeric scalars). A known field number with a wire type that does not fit its kind is kept as an unknown field. Unknown fields are preserved byte-exactly and re-emitted. Errors raise `DecodeException` with the byte offset: truncated varint/fixed/length, length beyond buffer, varint overflow, field number 0 or tag > 32 bits, wire type 3/4/6/7, invalid UTF-8, packed payload not a whole number of elements, nesting deeper than 32.
- `toString()` of a Msg is deterministic text: `Name{a=150, s="testing", xs=[1, 2]}`.

## Success criteria
- `mvn -q -B clean test` passes.
- Literal vectors from the public protobuf encoding guide (150 → `96 01`; field 1 = 150 → `08 96 01`; field 2 "testing" → `12 07 74 65 73 74 69 6e 67`; nested field 3 → `1a 03 08 96 01`; packed field 4 = [3, 270, 86942] → `22 06 03 8e 02 9e a7 05`).
- Randomised differential: random messages round-trip and re-encode to identical bytes; varints cross-checked against `BigInteger` groups; every truncation and every single-byte flip of valid messages either decodes or throws `DecodeException` (never another exception).
