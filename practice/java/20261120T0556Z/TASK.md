# Task: ae.roaring — compressed bitmaps and a bitmap-index query engine (level 20 capstone)

Overlap check: `benchmark/java/` has j10-counter, j16-ranges, j23-segtree, j35-minisql among others. None is a compressed
integer set, a bit-sliced index, or a bitmap-index boolean query engine. j16-ranges/j05-intervals are interval merging;
this task only uses runs as a storage encoding. j35-minisql is SQL over rows; this is set algebra over row-id bitmaps.

## Goal
Build, with the JDK only:

1. `RoaringBitmap`: set of unsigned 32-bit ints (an `int` argument is read as unsigned, order is unsigned) split by the high
   16 bits into containers of three kinds:
   - array container (sorted `char[]`, used when cardinality <= 4096),
   - bitmap container (1024 longs, cardinality > 4096),
   - run container (start,length pairs; only produced by `runOptimize()` or by range operations, chosen when its byte size
     `2 + 4*runs` is strictly smaller than the array/bitmap size `min(2*card if card<=4096 else 8192)`).
   API: `add`, `remove`, `contains`, `cardinality()` (long), `isEmpty`, `addRange(long start,long endExclusive)`,
   `removeRange`, `flip(start,end)`, static `and/or/xor/andNot` (operands never modified, results contain only array/bitmap
   containers), `runOptimize()`, `rank(x)` (# elements <= x, unsigned), `select(long i)`, `first()`, `last()`, `toArray()`,
   `forEach(IntConsumer)`, `copy()`, content-based `equals/hashCode` (independent of container kinds),
   `serialize()` / static `deserialize(byte[])`.
2. Sealed format: `MAGIC(int 0x524F4152) VERSION(byte 1) N(int) {key(u16) kind(byte 0 array,1 bitmap,2 run) payload}* CRC32(int)`.
   array payload: `card(int) values(u16...)`; bitmap payload: 1024 longs; run payload: `runs(int) {start(u16) lenMinus1(u16)}*`.
   CRC32 covers every earlier byte. Reject: bad magic/version, truncation, trailing bytes, CRC mismatch, keys not strictly
   increasing, array values not strictly increasing, empty containers, array containers with more than 4096 values, bitmap containers with 4096 or fewer values,
   runs overlapping/adjacent/out of order/past 65535 — always with `CorruptBitmapException` (unchecked) and a descriptive message.
3. `BitSlicedIndex`: bit-sliced index over non-negative int column values (row = array index): `equalTo`, `lessThan`,
   `lessOrEqual`, `greaterThan`, `greaterOrEqual`, `between(lo,hi)`, `sum(filter)`, `count()`; values < 0 rejected.
4. `BitmapTable` + `Query`: a read-only table with declared categorical (string) and int columns built from rows; boolean query
   language `expr := or; or := and (OR and)*; and := not (AND not)*; not := NOT not | atom;
   atom := ( expr ) | col = value | col != value | col IN (v, v...) | col <|<=|>|>= int | col BETWEEN int AND int`.
   Keywords are case-insensitive; values are bare words or `'quoted'`. `eval(query)` returns the matching row ids as a
   `RoaringBitmap`; `count(query)`; `sum(intColumn, query)`. Errors: `QueryException` with the 1-based column of the offending token.

## Success criteria
- Differential tests against `TreeSet<Long>` for all operations across all container kinds (including unsigned extremes).
- Package-private `checkInvariants()` after every mutation in randomised tests.
- Serialisation: round trip for every kind, every truncation, every single-byte flip rejected, crafted images re-sealed with
  a valid CRC rejected for the structural reasons (with a control input differing only by the defect that loads fine).
- Bit-sliced comparisons and queries checked against brute force; generated random query ASTs compared with row-by-row evaluation.
- `mvn -q -B test` passes.
