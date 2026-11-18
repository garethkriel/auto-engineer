# Java skills index

All skills pass `mvn -q -B test` (verified 20261118T0056Z (new rows: markdown-lite, slotted-heap-file, sstable, toml-subset-parser, ts-block-file)). See each folder's README.

| Skill | Use it for |
|---|---|
| account-static-state | static vs instance state, atomic transfers |
| array-ops | int[] rotation, second largest, runs |
| backtrack-toolkit | permutations, N-Queens, combination sum backtracking |
| business-calendar | java.time business-day arithmetic |
| circuit-breaker | circuit breaker, generation-tagged permits, injectable clock |
| dir-snapshot-diff | SHA-256 directory snapshots, atomic persistence, diffs |
| dp-toolkit | coin change, knapsack, LIS with solution reconstruction |
| file-kv-store | file-backed key-value store |
| fraction | exact rational value class |
| glob-matcher | path globs with memoised matching |
| int-range | half-open range value class |
| inverted-index | tokenising, set queries, Comparator chains |
| keyed-executor | serial-per-key, parallel-across-keys execution |
| leaderboard-ranking | competition ranking, grouping |
| loan-amortization | BigDecimal schedules |
| micro-batcher | size-or-time batching with injectable scheduler |
| markdown-lite | Markdown-subset parser to sealed AST, safe HTML renderer, positioned errors |
| middleware-pipeline | chain-of-responsibility pipeline, router, auth/recover/trace |
| object-pool | bounded blocking resource pool, slot accounting |
| order-analytics | custom Collector, immutable monoid stats, grouping |
| parallel-helpers | bounded ordered parallel map, firstSuccess, timeouts |
| persistent-collections | immutable persistent stack and queue |
| prime-sieve-number-theory | odd-only bit sieve, Miller-Rabin, Pollard-rho, overflow-free modPow |
| primitive-collections | IntBitSet (rank/select), open-addressing IntIntMap with backward-shift delete |
| retry-backoff | retry with injectable sleeper |
| rotating-log | size-based log rotation with numbered backups |
| single-flight | per-key call coalescing via CompletableFuture |
| slotted-heap-file | slotted pages, record ids, per-page CRC32, compaction, atomic flush |
| sql-select-builder | immutable parameterised SELECT builder, Dialect strategy |
| sstable | immutable sorted table, Bloom filter, sparse index, tombstones, k-way merge |
| string-ops | RLE, palindromes, capitalisation |
| sudoku-bitmask | bitmask Sudoku solver, solution counting with limit |
| template-engine | mini template engine: filters, if/for, positioned syntax errors |
| text-table | aligned plain-text tables |
| toml-subset-parser | TOML-subset parser, sealed values, line/column errors, depth cap |
| ts-block-file | delta-of-delta varint blocks, per-block CRC, footer index, atomic write |
| typed-container-state-machine | type tokens, EnumMap state machine |
| typed-event-bus | observer bus with snapshot dispatch |
| typed-result | sealed Result, PECS helpers |
| undo-redo-editor | Command pattern with self-inverse apply, undo/redo |
| validator-lazy | error-accumulating validators, thread-safe memoising Lazy |
