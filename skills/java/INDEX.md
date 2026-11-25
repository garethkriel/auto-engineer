# Java skills index

All skills pass `mvn -q -B test` (the nine rows autodiff-mlp, boolean-minimizer, geohash-toolkit, kademlia-dht, paxos-log, proto-wire-codec, shell-word-expansion, succinct-structures, torrent-core verified 20261125T1856Z; the five rows cache-hierarchy-sim, dns-toolkit, hashlife, ipv4-toolkit, merkle-sync verified 20261125T0656Z; the five rows elevator-sim, game-tree-search, paragraph-layout, poly-toolkit, rb-tree-map verified 20261125T0057Z; the four rows aho-corasick, columnar-store, persistent-rope, timer-wheel verified 20261124T1857Z; the three rows logic-simulator, range-coder-compressor, utxo-ledger verified 20261124T1256Z; the five rows bigint-from-scratch, cidr-toolkit, minilang-toolchain, mqtt-broker-core, prolog-engine verified 20261124T0656Z; verified 20261120T0656Z for earlier rows; the five new rows verified 20261120T1257Z (rows added this round: chess-rules, exact-geometry, mini-sql-engine, regex-automata, suffix-toolkit; earlier round: rows added this round: erasure-coded-blob-store, flow-toolkit, gorilla-timeseries, peg-engine, roaring-bitmap-index; earlier round: rows added this round: btree-buffer-pool, exact-simplex-lp, resp-mini-server, rsync-delta; earlier round: rows added this round: cdcl-sat-solver, heap-gc, hm-type-inference, probabilistic-sketches, swim-membership; earlier round: bitemporal-store, coord-service, datalog-engine, deflate-codec, incremental-build, lock-manager, partitioned-log-broker, spatial-quadtree, windowed-aggregation; earlier new rows: content-addressed-vcs, job-scheduler, mvcc-store, semver-resolver, vfs-inode-model)). See each folder's README.

| Skill | Use it for |
|---|---|
| account-static-state | static vs instance state, atomic transfers |
| aho-corasick | multi-pattern string matching: trie + failure links, overlapping findAll, leftmost-longest, replaceAll, chunk-independent streaming matcher |
| array-ops | int[] rotation, second largest, runs |
| autodiff-mlp | reverse-mode autodiff over small matrices (broadcast add, matmul, tanh/relu/sigmoid, mean), iterative backward, finite-difference GradCheck, seeded MLP on XOR |
| backtrack-toolkit | permutations, N-Queens, combination sum backtracking |
| bigint-from-scratch | arbitrary-precision integers from scratch: Karatsuba, Knuth D division, modPow/modInverse, radix I/O (BigInteger as oracle) |
| bitemporal-store | bitemporal key/value store: valid-time timelines, as-of reads, atomic transactions |
| boolean-minimizer | exact Quine-McCluskey boolean minimisation: prime implicants, essential/dominance reduction, branch-and-bound minimum SOP, Sop text/parse |
| btree-buffer-pool | disk-backed B+tree over a pinning LRU buffer pool, free list, invariant checker |
| business-calendar | java.time business-day arithmetic |
| cache-hierarchy-sim | set-associative cache simulator: LRU/FIFO/PLRU/random, write policies, two-level hierarchy, AMAT, Belady OPT bound |
| cdcl-sat-solver | CDCL SAT solver with watched literals, learning, restarts, DIMACS, RUP proof checker |
| chess-rules | full chess rules engine: FEN/SAN, legal moves, make/unmake, perft, mate search |
| cidr-toolkit | IPv4 CIDR set algebra, longest-prefix route trie with aggregation, firewall shadow analysis |
| circuit-breaker | circuit breaker, generation-tagged permits, injectable clock |
| columnar-store | column segments with RLE/dict/FOR/delta encodings, zone-map pruning, CRC-sealed strict decoder, segmented table scan/aggregate |
| consistent-hash-ring | weighted virtual-node hash ring, preference lists, bounded-load, rebalance diff |
| content-addressed-vcs | SHA-256 object store, commits, merge-base, three-way merge, fsck |
| coord-service | ZooKeeper-style kernel: sessions, ephemeral/sequential nodes, watches, multi, fenced lock |
| crdt-toolkit | state-based CRDTs (GCounter, PNCounter, LWW register, OR-set) with join merge |
| datalog-engine | Datalog parser + stratified negation, naive and semi-naive evaluation |
| deflate-codec | DEFLATE/zlib codec: LZ77, Huffman, bit streams, Adler-32, JDK oracle tests |
| dir-snapshot-diff | SHA-256 directory snapshots, atomic persistence, diffs |
| dns-toolkit | DNS wire codec with compression, zones (wildcards, delegation), authoritative server, caching iterative resolver |
| dp-toolkit | coin change, knapsack, LIS with solution reconstruction |
| elevator-sim | tick-based elevator bank: cost-based hall-call dispatch, SCAN stops, capacity, livelock-safe eligibility, invariant checker |
| erasure-coded-blob-store | Reed-Solomon (GF(256) Cauchy) erasure-coded blob store: shard files, CRC, scrub, repair |
| event-sourced-cart | event sourcing: pure decide/apply fold, optimistic-concurrency store, checkpointed projections |
| exact-geometry | exact integer geometry: hull, segment intersection, point-in-polygon, closest pair, sweep |
| exact-simplex-lp | exact rational simplex LP solver with Bland rule and duality/Farkas certificates |
| file-kv-store | file-backed key-value store |
| flow-toolkit | Dinic max-flow/min-cut, min-cost flow, Hopcroft-Karp, Hungarian, shift planner |
| fraction | exact rational value class |
| game-tree-search | negamax alpha-beta, transposition table, iterative deepening, move ordering; ConnectK and Nim games, minimax/perft oracles |
| geohash-toolkit | geohash encode/decode via cell indices, 8-direction neighbours with antimeridian wrap, sorted box cover, haversine |
| glob-matcher | path globs with memoised matching |
| gorilla-timeseries | Gorilla delta-of-delta/XOR time-series compression, sealed blocks, retention, snapshots |
| hashlife | Game of Life via HashLife: hash-consed quadtree, step(n), RLE, compact(), naive oracle |
| heap-gc | simulated heap with mark-sweep/compaction, free-list allocators, invariant checker |
| hm-type-inference | Hindley-Milner type inference: parser, unification, let-polymorphism |
| incremental-build | incremental build engine: dynamic deps, early cutoff, persistence |
| indexed-priority-queue | d-ary indexed min-heap, changeKey/remove by id, deterministic ties |
| int-range | half-open range value class |
| inverted-index | tokenising, set queries, Comparator chains |
| ipv4-toolkit | IPv4 checksum (incremental), packet codec, fragmenter, reassembler, UDP pseudo-header checksum |
| job-scheduler | durable job scheduler: deps, retries with backoff, journal replay recovery |
| kademlia-dht | in-process Kademlia DHT: XOR NodeId, LRU k-buckets with replacement cache, iterative lookup, put/get, refresh, fault injection |
| keyed-executor | serial-per-key, parallel-across-keys execution |
| leaderboard-ranking | competition ranking, grouping |
| loan-amortization | BigDecimal schedules |
| lock-manager | multi-granularity lock manager: IS/IX/S/SIX/X, FIFO queues, deadlock detection |
| logic-simulator | gate-level netlist parser, levelised/64-way packed eval, event-driven simulator with glitches, critical path, equivalence check |
| markdown-lite | Markdown-subset parser to sealed AST, safe HTML renderer, positioned errors |
| merkle-sync | 16-ary Merkle hash trie map, inclusion/non-inclusion proofs, verified anti-entropy sync |
| micro-batcher | size-or-time batching with injectable scheduler |
| middleware-pipeline | chain-of-responsibility pipeline, router, auth/recover/trace |
| mini-sql-engine | in-memory SQL engine: parser, joins (naive vs hash), 3-valued logic, GROUP BY, transactions |
| minilang-toolchain | tiny-language lexer/parser/checker/optimiser/bytecode compiler/verifier/VM with four-engine differential tests |
| mqtt-broker-core | MQTT-style broker: wildcard subscription trie, sessions, retained/will, QoS 1 redelivery, invariant checker |
| mvcc-store | multi-version KV store: snapshot/serializable txns, vacuum |
| notification-renderer | strategy-pattern refactor with characterisation + differential tests |
| object-pool | bounded blocking resource pool, slot accounting |
| order-analytics | custom Collector, immutable monoid stats, grouping |
| paragraph-layout | Knuth-Plass optimal line breaking over box/glue/penalty items, justified text with hyphenation, brute-force oracle |
| parallel-helpers | bounded ordered parallel map, firstSuccess, timeouts |
| partitioned-log-broker | Kafka-style partitioned log, consumer groups, rebalance, at-least-once |
| paxos-log | multi-decree Paxos replicated log with NOOP gap filling, Sync catch-up and a seeded fault-injecting Cluster with safety checker |
| peg-engine | PEG grammar parser with static analysis, packrat interpreter, farthest-failure errors |
| persistent-collections | immutable persistent stack and queue |
| persistent-rope | immutable AVL rope: O(log n) concat/split/insert/delete, line index, streaming KMP search |
| poly-toolkit | polynomials mod 998244353: NTT, Newton inverse, divMod, Lagrange, Shamir sharing, Berlekamp-Massey nthTerm |
| prime-sieve-number-theory | odd-only bit sieve, Miller-Rabin, Pollard-rho, overflow-free modPow |
| primitive-collections | IntBitSet (rank/select), open-addressing IntIntMap with backward-shift delete |
| probabilistic-sketches | Bloom filter, Count-Min and HyperLogLog sketches with sealed serialization |
| prolog-engine | Prolog subset: operator-precedence parser, iterative SLD with cut, findall, assert/retract, exact arithmetic |
| proto-wire-codec | protobuf-style wire codec: varint/zigzag, schema parser, dynamic Msg, packed fields, unknown-field preservation, strict offsets |
| raft-lite | Raft election/replication plus seeded fault-injecting cluster simulator |
| range-coder-compressor | carry-propagating range coder, Fenwick adaptive model, context-model byte compressor with CRC container |
| rb-tree-map | order-statistic left-leaning red-black tree map: rank/select/countRange, floor/ceiling, invariant checker |
| refactor-library-lending | god-class refactor: enum tiers, sealed Result, guard-order differential grid |
| regex-automata | regex to NFA/DFA, minimisation, canonical form, equivalence with witness |
| resilient-http | HttpClient wrapper: safe retries, backoff, Retry-After, idempotency keys |
| resp-mini-server | Redis-style RESP server with expiry, transactions and AOF crash recovery |
| retry-backoff | retry with injectable sleeper |
| roaring-bitmap-index | Roaring bitmaps (array/bitmap/run containers), bit-sliced index, boolean query parser |
| role-policy-acl | string-switch to enum-with-behaviour refactor, differential grid oracle |
| rollback-dsu | union-find with undo, offline dynamic connectivity |
| rotating-log | size-based log rotation with numbered backups |
| rsync-delta | rsync-style rolling-checksum delta sync with atomic directory writes |
| saga-coordinator | write-ahead-log saga, compensation, crash resume |
| semver-resolver | semver constraints, backtracking dependency resolver, lockfile |
| shell-word-expansion | bash-subset word expansion: quotes, brace expansion, ${..} parameter forms, word splitting; bash as differential oracle |
| single-flight | per-key call coalescing via CompletableFuture |
| skip-list-map | indexable skip list: rank/select/countRange, navigation, invariant checker |
| slotted-heap-file | slotted pages, record ids, per-page CRC32, compaction, atomic flush |
| spatial-quadtree | point quadtree: range/radius/kNN, polygon containment, DBSCAN, CRC snapshots |
| sql-select-builder | immutable parameterised SELECT builder, Dialect strategy |
| sstable | immutable sorted table, Bloom filter, sparse index, tombstones, k-way merge |
| string-ops | RLE, palindromes, capitalisation |
| succinct-structures | BitVector rank/select, WaveletMatrix (kthSmallest, rangeCount), Elias-Fano with exact space formula |
| sudoku-bitmask | bitmask Sudoku solver, solution counting with limit |
| suffix-toolkit | suffix array, LCP, BWT, FM-index, substring search |
| swim-membership | SWIM failure detection and membership gossip simulation with invariant checker |
| tcp-relay | loopback TCP proxy: half-close, shared idle timeout, connection cap |
| template-engine | mini template engine: filters, if/for, positioned syntax errors |
| text-table | aligned plain-text tables |
| timer-wheel | hierarchical timing wheel in virtual time: periodic timers, O(1) cancel, reentrant callbacks, heap oracle tests |
| toml-subset-parser | TOML-subset parser, sealed values, line/column errors, depth cap |
| torrent-core | strict bencode, metainfo with SHA-1 info hash, rarest-first PiecePicker, deterministic swarm simulation with poisoners |
| transactional-outbox | transactional outbox relay, per-key ordering, idempotent receiver |
| ts-block-file | delta-of-delta varint blocks, per-block CRC, footer index, atomic write |
| typed-container-state-machine | type tokens, EnumMap state machine |
| typed-event-bus | observer bus with snapshot dispatch |
| typed-result | sealed Result, PECS helpers |
| udp-stop-and-wait | reliable UDP transfer: ACK/retransmit, dedupe, index-based loss injection |
| undo-redo-editor | Command pattern with self-inverse apply, undo/redo |
| utxo-ledger | UTXO blockchain: Merkle proofs, PoW blocks, atomic apply/undo, reorg to heavier chain |
| validator-lazy | error-accumulating validators, thread-safe memoising Lazy |
| vfs-inode-model | in-memory POSIX-like filesystem: inodes, links, perms, quota, fsck |
| windowed-aggregation | event-time windows (tumbling/sliding/session), watermarks, lateness, snapshots |
