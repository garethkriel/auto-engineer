# 20261126T0556Z — ae.ot: operational transformation for collaborative text editing

Level 20 capstone, Java 21, standard library only.

## Overlap check
`benchmark/java/` directory names read this session: j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql. None is text OT (j18-diff is a line diff, j34-vclock is vector clocks, not operation transformation). Existing skills `crdt-toolkit` and `undo-redo-editor` are different algorithms (CRDT merge; local undo stack).

## Design
* `TextOp` (immutable): a list of components `Retain(n)`, `Insert(text)`, `Delete(n)` describing a change to a document of exactly `baseLength()` chars producing `targetLength()` chars.
  * Canonical form built by `TextOp.Builder`: zero-length parts dropped, adjacent same-kind parts merged, and within a run of inserts/deletes the insert comes first (`Delete,Insert` is stored as `Insert,Delete`). Equal documents-effects built in different ways therefore compare `equals`. Trailing retains are kept (lengths are explicit).
  * `apply(String)`, `compose(next)` (apply this then next), `invert(docBefore)`, `transformIndex(i, advanceOverInsert)` for cursors, `static transform(a, b)`.
  * `transform(a, b)` (both from the same document) returns `Pair(left, right)` where `left` is `a` rewritten to apply after `b`, `right` is `b` rewritten to apply after `a`. TP1: `a.compose(right)` and `b.compose(left)` have the same effect. Tie-break: when both insert at the same position, **a's text goes first**. A char deleted by both is deleted once.
* `OtServer(initialDoc)`: linear history. `submit(baseRevision, op)` transforms `op` against `history[baseRevision..]` with the committed ops taking priority, applies it, appends it, returns `Result(revision, transformedOp)`. Invalid input (revision out of range, wrong base length) throws `IllegalArgumentException` and leaves the server unchanged.
* `OtClient(initialDoc)`: Jupiter/ot.js state machine `Synced -> AwaitingAck(outstanding) -> AwaitingWithBuffer(outstanding, buffer)`. At most one op is in flight; later local edits are composed into the buffer. `localEdit(op)` returns the message to send (if any), `serverAck(rev)` returns the buffered message to send next (if any), `serverOp(rev, op)` transforms the remote op over outstanding/buffer (remote has priority) and applies it. Revisions must arrive consecutively or `IllegalStateException`.
* `OtSimulation(seed, clients, initialDoc)`: seeded random scheduler over FIFO channels (client->server, server->client) that interleaves random local edits and deliveries, with invariant checks after every delivery; `RandomOps.generate(rng, doc, alphabet)` makes random multi-component ops.

## Success criteria
1. `mvn -q -B clean test` passes in this folder.
2. Literal examples for every operation; TP1 plus provenance invariants (surviving original chars keep order, inserted strings stay contiguous, tie order is the documented one) on random op pairs with unique characters.
3. `compose` is checked against sequential `apply` and associativity; `invert` round-trips; `transformIndex` table.
4. Client state machine literal scenarios (buffer composes, exactly one op in flight) and invalid-message rejections that leave state unchanged.
5. Randomised multi-client simulations over many seeds all converge to the server doc, and the run mix really contains concurrent conflicts (asserted).
6. Mutation pass over `src/main`: every surviving mutant is either closed with a test or justified as equivalent.

## Assumptions
* Characters are UTF-16 code units (no surrogate handling); documented.
* No cursor/selection syncing beyond the pure `transformIndex` helper.
