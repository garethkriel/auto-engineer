# roaring-bitmap-index

- **Language:** Java 21 (JDK only)
- **Tags:** compressed bitmap, roaring, run-length, bit-sliced index, bitmap index, boolean query parser, CRC-sealed format
- **Source round:** `practice/java/20261120T0556Z`

`ae.roaring.RoaringBitmap` stores unsigned 32-bit sets in array/bitmap/run containers (canonical size rules, ties go to
array/bitmap), with `and/or/xor/andNot`, range add/remove/flip (a range over a missing key becomes a single run, so
`addRange(0, 2^32)` is 65536 tiny containers), `rank/select`, and a CRC32-sealed serialisation that validates structure.
`BitSlicedIndex` answers `=,<,<=,>,>=,between,sum` over an int column. `BitmapTable` parses
`NOT > AND > OR` queries with `IN`, `BETWEEN`, quoted values and column-positioned errors.

Pitfalls tested: unsigned order, 4096/4097 promotion boundary, run-vs-array tie, set-op results never alias operands,
re-sealed crafted images rejected structurally.
