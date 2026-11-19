# Java skills index

All skills pass `mvn -q -B test` (verified 20261119T1256Z (rows added this round: bitemporal-store, coord-service, datalog-engine, deflate-codec, incremental-build, lock-manager, partitioned-log-broker, spatial-quadtree, windowed-aggregation; earlier new rows: content-addressed-vcs, job-scheduler, mvcc-store, semver-resolver, vfs-inode-model)). See each folder's README.

| Skill | Use it for |
|---|---|
| account-static-state | static vs instance state, atomic transfers |
| array-ops | int[] rotation, second largest, runs |
| backtrack-toolkit | permutations, N-Queens, combination sum backtracking |
| bitemporal-store | bitemporal key/value store: valid-time timelines, as-of reads, atomic transactions |
| business-calendar | java.time business-day arithmetic |
| circuit-breaker | circuit breaker, generation-tagged permits, injectable clock |
| consistent-hash-ring | weighted virtual-node hash ring, preference lists, bounded-load, rebalance diff |
| content-addressed-vcs | SHA-256 object store, commits, merge-base, three-way merge, fsck |
| coord-service | ZooKeeper-style kernel: sessions, ephemeral/sequential nodes, watches, multi, fenced lock |
| crdt-toolkit | state-based CRDTs (GCounter, PNCounter, LWW register, OR-set) with join merge |
| datalog-engine | Datalog parser + stratified negation, naive and semi-naive evaluation |
| deflate-codec | DEFLATE/zlib codec: LZ77, Huffman, bit streams, Adler-32, JDK oracle tests |
| dir-snapshot-diff | SHA-256 directory snapshots, atomic persistence, diffs |
| dp-toolkit | coin change, knapsack, LIS with solution reconstruction |
| event-sourced-cart | event sourcing: pure decide/apply fold, optimistic-concurrency store, checkpointed projections |
| file-kv-store | file-backed key-value store |
| fraction | exact rational value class |
| glob-matcher | path globs with memoised matching |
| incremental-build | incremental build engine: dynamic deps, early cutoff, persistence |
| indexed-priority-queue | d-ary indexed min-heap, changeKey/remove by id, deterministic ties |
| int-range | half-open range value class |
| inverted-index | tokenising, set queries, Comparator chains |
| job-scheduler | durable job scheduler: deps, retries with backoff, journal replay recovery |
| keyed-executor | serial-per-key, parallel-across-keys execution |
| leaderboard-ranking | competition ranking, grouping |
| loan-amortization | BigDecimal schedules |
| lock-manager | multi-granularity lock manager: IS/IX/S/SIX/X, FIFO queues, deadlock detection |
| markdown-lite | Markdown-subset parser to sealed AST, safe HTML renderer, positioned errors |
| micro-batcher | size-or-time batching with injectable scheduler |
| middleware-pipeline | chain-of-responsibility pipeline, router, auth/recover/trace |
| mvcc-store | multi-version KV store: snapshot/serializable txns, vacuum |
| notification-renderer | strategy-pattern refactor with characterisation + differential tests |
| object-pool | bounded blocking resource pool, slot accounting |
| order-analytics | custom Collector, immutable monoid stats, grouping |
| parallel-helpers | bounded ordered parallel map, firstSuccess, timeouts |
| partitioned-log-broker | Kafka-style partitioned log, consumer groups, rebalance, at-least-once |
| persistent-collections | immutable persistent stack and queue |
| prime-sieve-number-theory | odd-only bit sieve, Miller-Rabin, Pollard-rho, overflow-free modPow |
| primitive-collections | IntBitSet (rank/select), open-addressing IntIntMap with backward-shift delete |
| raft-lite | Raft election/replication plus seeded fault-injecting cluster simulator |
| refactor-library-lending | god-class refactor: enum tiers, sealed Result, guard-order differential grid |
| resilient-http | HttpClient wrapper: safe retries, backoff, Retry-After, idempotency keys |
| retry-backoff | retry with injectable sleeper |
| role-policy-acl | string-switch to enum-with-behaviour refactor, differential grid oracle |
| rollback-dsu | union-find with undo, offline dynamic connectivity |
| rotating-log | size-based log rotation with numbered backups |
| saga-coordinator | write-ahead-log saga, compensation, crash resume |
| semver-resolver | semver constraints, backtracking dependency resolver, lockfile |
| single-flight | per-key call coalescing via CompletableFuture |
| skip-list-map | indexable skip list: rank/select/countRange, navigation, invariant checker |
| slotted-heap-file | slotted pages, record ids, per-page CRC32, compaction, atomic flush |
| spatial-quadtree | point quadtree: range/radius/kNN, polygon containment, DBSCAN, CRC snapshots |
| sql-select-builder | immutable parameterised SELECT builder, Dialect strategy |
| sstable | immutable sorted table, Bloom filter, sparse index, tombstones, k-way merge |
| string-ops | RLE, palindromes, capitalisation |
| sudoku-bitmask | bitmask Sudoku solver, solution counting with limit |
| tcp-relay | loopback TCP proxy: half-close, shared idle timeout, connection cap |
| template-engine | mini template engine: filters, if/for, positioned syntax errors |
| text-table | aligned plain-text tables |
| toml-subset-parser | TOML-subset parser, sealed values, line/column errors, depth cap |
| transactional-outbox | transactional outbox relay, per-key ordering, idempotent receiver |
| ts-block-file | delta-of-delta varint blocks, per-block CRC, footer index, atomic write |
| typed-container-state-machine | type tokens, EnumMap state machine |
| typed-event-bus | observer bus with snapshot dispatch |
| typed-result | sealed Result, PECS helpers |
| udp-stop-and-wait | reliable UDP transfer: ACK/retransmit, dedupe, index-based loss injection |
| undo-redo-editor | Command pattern with self-inverse apply, undo/redo |
| validator-lazy | error-accumulating validators, thread-safe memoising Lazy |
| vfs-inode-model | in-memory POSIX-like filesystem: inodes, links, perms, quota, fsck |
| windowed-aggregation | event-time windows (tumbling/sliding/session), watermarks, lateness, snapshots |
