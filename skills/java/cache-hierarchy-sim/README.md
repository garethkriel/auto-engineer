# cache-hierarchy-sim

- Description: CPU cache simulator (package `ae.cachesim`): set-associative caches with LRU/FIFO/tree-PLRU/seeded-random replacement, write-back/write-allocate or write-through/no-allocate, a two-level `Hierarchy` with AMAT, and Belady's `OptSimulator` lower bound.
- Language: java
- Tags: cache, simulation, replacement-policy, plru, belady, amat, differential-testing
- Source round: practice/java/20261125T0156Z
- Tests: literal traces (PLRU vs LRU divergence, FIFO Belady anomaly 9 vs 10, matrix walk 64 vs 1024 misses), naive-list oracle differential, 2-way PLRU == LRU, LRU stack property, OPT bound, hierarchy conservation.
