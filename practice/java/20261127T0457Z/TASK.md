# Task: ae.xsort — external merge sort with a hard memory budget

Level 20 capstone (combines file I/O, heaps, tournament trees, crash-safe output).

## Overlap check
Read `benchmark/java/` names (j01-roman .. j35-minisql): none is an external sort. Skills: `sstable` has a k-way `SstMerge` for sorted tables, but no run generation, no memory budget, no loser tree; `grep -ri "replacement selection\|loser"` found nothing in skills/practice.

## Design
Sort a stream of records that does not fit in memory using at most `memoryRecords` records in RAM (plus `fanIn` heads during a merge) and temporary run files.

Package `ae.xsort`:
- `Codec<T>`: `write(DataOutput,T)`, `read(DataInput)`; `Codecs.STRING` (int length + UTF-8, no 64K limit), `Codecs.LONG`.
- `RunFile`: temp file = sequence of `[1][record]` entries ended by a `[0]` marker. A file missing the marker (truncated) makes the reader throw `CorruptRunException`.
- `ReplacementSelection.generate(input, cmp, M, sink)`: heap of at most M records ordered by (run number, key, arrival sequence). A new record smaller than the last record written joins the next run. Sorted input gives one run, reverse-sorted gives runs of exactly M, random input gives runs of about 2M. Stable: ties keep arrival order.
- `LoserTree<T>`: k-way merge over iterators; exhausted sources lose; ties break by source index (so merging runs in creation order is stable).
- `ExternalSorter<T>(codec, cmp, memoryRecords, fanIn, tempDir)`:
  `Stats sort(Iterator<T> in, Consumer<T> out)`. Runs are merged in groups of `fanIn` consecutive runs (stability) until at most `fanIn` remain; the last merge streams straight to `out`. Consumed run files are deleted immediately; all temp files are deleted on every exit path (success, codec/comparator/consumer failure).
  `Stats sortLines(Path in, Path out)`: lines (`\n`, `\r\n` accepted; output uses `\n`), natural String order by code point? -> uses `String.compareTo` (UTF-16 order). Output is written to a temp file in the output directory then moved atomically, so a failed sort never leaves a partial output.
- `Stats` record: `recordsRead`, `runLengths` (list), `mergePasses`, `peakRecordsInMemory`, `tempFilesCreated`.
  `mergePasses`: 0 if there are 0 or 1 runs, else the number of merges levels = ceil(log_fanIn(runs)) (the final merge to the consumer counts).

## Success criteria
- Differential: output equals `List.sort` (stable) on random inputs for many (n, M, fanIn) combos, including ties tracked by a sequence number.
- Literal run-generation example (M=3, input 5,1,9,2,8,3,7,4,6 -> runs [1,2,5,8,9] and [3,4,6,7]); sorted -> 1 run; reverse -> ceil(n/M) runs; random average run > 1.6 M; peak memory <= M.
- Loser tree equals naive merge with source-index tie-break, for k = 1..9 incl. empty sources.
- Stats formulas hold; temp dir empty after success and after every injected failure; truncated run file detected; failed `sortLines` leaves no output file.
- Argument validation. `mvn -q -B clean test` passes.
