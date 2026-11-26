# Task: Unicode transformation toolkit (`ae.utf`)

Level: 10 (capped; recorded level from `tools/curriculum.py` is 20). Topic: capstone — text encodings.

Overlap check: `benchmark/java/` names (j01-roman … j35-minisql) are about Roman numerals, word frequency, LRU, CSV, intervals, LCS,
expressions, graphs, banking, counters, streams, file index, rate limit, JSON, topo sort, ranges, cache, diff, allocation, cron,
spreadsheet, regex, segment tree, queues, business hours, rwlock, primes, scripting, kv log, mini server, ranking, FSM, pricing,
vector clocks, mini SQL. None is a text-encoding task. Skills already present have no UTF/Punycode toolkit.

## Components
1. `Utf8`: `encode(int[])` (rejects surrogates / > 0x10FFFF), `decodeStrict(byte[])` (throws `CodecException` with the offset
   of the START of the first invalid sequence), `decodeLossy(byte[])` (WHATWG semantics: each *maximal invalid subpart* becomes one U+FFFD).
2. `Utf8Decoder`: streaming decoder, `feed(bytes, off, len)` / `finish()`; result must be independent of how the input is chunked.
   Constructed lossy or strict. Strict offsets are absolute stream offsets.
3. `Utf16`: `encode(int[], bigEndian, bom)`, `decode(byte[], bigEndian)` strict (odd length, lone surrogates throw), `detectBom(byte[])`.
4. `Punycode` (RFC 3492): `encode(int[])`, `decode(String)` with overflow and invalid-digit detection, case-insensitive digits.

## Success criteria
- RFC 3492 sample vectors; JDK differential tests (UTF-8 strict/lossy decoders, UTF-16 LE/BE, `java.net.IDN`).
- Random chunking equals one-shot decode; exhaustive tests of all 1-, 2- and 3-byte prefixes of the invalid-sequence rules.
- `mvn -q -B test` passes.

## Assumptions
- Lossy semantics = WHATWG Encoding Standard UTF-8 decoder (maximal subpart); Java's CharsetDecoder REPLACE is used as oracle and
  suspected first-hand-wrong only after checking the spec table.
- Code points are `int[]`; no `String` convenience beyond Punycode labels.
