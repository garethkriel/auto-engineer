# bitemporal-store

- **Description:** In-memory bitemporal key/value store (`ae.bitemporal.BitemporalStore`): valid-time half-open intervals on an immutable coalescing `Timeline`, transaction-time history with as-of reads, atomic multi-key transactions, no-op transactions not recorded, injected non-decreasing clock, `diff` between two knowledge states, and per-instant `revisions` (how belief changed).
- **Language:** java
- **Tags:** bitemporal, temporal-data, valid-time, as-of, audit, interval, transactions, capstone
- **Source round:** `practice/java/20261119T0856Z`

Reuse notes: keep one canonical (coalesced) immutable timeline per key per transaction so equality detects no-ops structurally. Tests include a 60-seed differential test against an array-per-transaction oracle with an invariant check after every transaction; five mutants (no coalescing, always record, clock check off, txAtTime `<`, no revision de-dup) are each killed.
