# external-merge-sort

- **Language:** Java 21 (standard library only)
- **Tags:** external-sort, replacement-selection, loser-tree, k-way-merge, temp-files, atomic-output, stable-sort, failure-injection
- **Source round:** `practice/java/20261127T0457Z`

`ae.xsort.ExternalSorter<T>`: stable sort of a record stream under a hard memory budget. Runs come from replacement selection (heap of at most M records ordered by run, key, arrival seq: sorted input = 1 run, reverse = runs of M, random about 2M). Runs are merged in groups of `fanIn` consecutive runs with a `LoserTree` (ties to lower source index) until at most `fanIn` remain, and the last merge streams to the consumer. `Stats` reports runs, merge levels (= ceil(log_fanIn runs)), peak memory and temp files. Run files end with a marker, so truncation raises `CorruptRunException`. Consumed runs are deleted immediately and everything is deleted on every exit path. `sortLines(in, out, ...)` sorts a UTF-8 text file and publishes the result by atomic move.

Reusable ideas: the loser tree (`LoserTree`) works for any k-way merge of sorted iterators; failure-injection tests (codec/comparator/consumer throwing at the n-th call) with a "temp dir is empty" assertion; asserting the file count inside the consumer proves early deletion.

Tests: `mvn -q -B test` (34 tests: literal hand-traced runs, differential vs `List.sort`, pass-count table, cleanup after injected failures, truncation at every byte).
