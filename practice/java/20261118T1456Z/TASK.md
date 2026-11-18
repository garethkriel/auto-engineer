# Task: refactor a messy library-lending class (level 18, Refactoring)

Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql) – none is a library lending/holds system.

## Starting code
`ae.lib.LegacyLibrary` (supplied, working but messy: Object[] records, parallel maps, string tiers and flags,
duplicated tier constants, results as magic strings). It is kept unchanged in the TEST tree as the oracle.

## Behaviour (all via String results; `day` is a non-negative int)
- `addBook(id,title,copies)`: ERR_ID (null/blank id), ERR_COPIES (<1), OK (new), OK_ADDED (existing; copies added, title kept).
- `join(member,tier)`: ERR_NAME, ERR_TIER (std/prem/kid, trimmed, case-insensitive), ERR_DUP, OK.
- `checkout(member,book,day)` guard order: ERR_DAY, NO_MEMBER, NO_BOOK, BLOCKED (owed >= 500), LIMIT (std 3/prem 6/kid 2),
  DUP_LOAN, UNAVAILABLE (no free copy), HELD_FOR_OTHERS (free copy but member is not head of queue), else `DUE:<day+period>`
  (std 14/prem 28/kid 7); head-of-queue member leaves the queue.
- `renew(member,book,day)`: ERR_DAY, NO_MEMBER, NO_LOAN, MAX_RENEWALS (std 1/prem 2/kid 0), HELD (queue non-empty), OVERDUE (day > due), else `DUE:<day+period>`.
- `giveBack(member,book,day)`: ERR_DAY, NO_MEMBER, NO_LOAN, else RETURNED or `RETURNED_FINE:<cents>`; fine = overdue days * rate (std 25/prem 10/kid 5), capped (1000, 1000, 300); added to owed.
- `hold(member,book)`: NO_MEMBER, NO_BOOK, HAS_LOAN, DUP_HOLD, AVAILABLE (free copy and empty queue), QUEUE_FULL (3), else `HOLD:<position>`.
- `payFine(member,cents)`: NO_MEMBER, ERR_AMOUNT (<=0), else `PAID:<p> LEFT:<l>` with p = min(cents, owed).
- Queries: `owed(member)` (-1 unknown), `available(book)` (-1 unknown), `queueLength(book)` (-1 unknown), `loansOf(member)` sorted `book:due` list (empty if unknown).

## Goal
Replace the god class with `ae.lib.Library` (same public signatures) built from small types: `Tier` enum (limits, period, rate, cap),
`Loan` record, `HoldQueue`, `Catalog`, sealed `Result`. Behaviour must be identical.

## Success
1. Characterisation tests (literal expectations) green against LegacyLibrary BEFORE refactoring.
2. Same table green against `Library`.
3. Seeded randomized differential test (several thousand operations) comparing every result string and all queries.
4. `mvn -q -B clean test` green.
