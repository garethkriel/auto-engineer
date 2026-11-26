# Task: operational-transformation collaborative text editor core (`ae.ot`)

Level: capstone (curriculum level 20, recorded as 10 per validator cap). Topic: capstone systems, new surface (text OT + client/server protocol + seeded network simulation).

Overlap check: `benchmark/java/` names j01-roman .. j35-minisql (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is OT or a collaborative editor. j18-diff is minimal line diff; this task never diffs texts.

## Design
- `TextOp`: immutable list of components `Retain(n)`, `Insert(s)`, `Delete(n)`, normalised by the builder: no zero-length parts, adjacent same-kind parts merged, an Insert placed before an adjacent Delete. `baseLength()` = retains+deletes, `targetLength()` = retains+inserts (in UTF-16 chars).
- `apply(String)` (requires `doc.length()==baseLength`), `compose(a,b)` (requires `a.target==b.base`), `transform(a,b)` -> `Pair(a', b')` (requires equal base) with `apply(apply(d,a),b') == apply(apply(d,b),a')`. Tie rule: when both insert at the same position, **a's text comes first**. `invert(doc)` returns the op undoing this op on `doc`.
- `Server`: document + revision log. `receive(clientId, baseRev, op)` transforms `op` over log entries `baseRev..rev-1` (log entry is the first argument, so history wins ties), applies, appends, returns `Accepted(rev, clientId, transformedOp)`. Invalid `baseRev` (negative or > rev) or wrong base length -> `IllegalArgumentException` with no state change.
- `Client`: three states Synchronized / AwaitingAck(out) / AwaitingAckWithBuffer(out, buf). `local(op)` applies locally and returns the op to send (or empty when it must wait). `remote(op)` transforms with server op as first argument. `ack()` returns the buffered op to send next (if any). `revision()` = number of server revisions processed (acks included).
- `Simulation`: N clients and a server joined by per-client FIFO channels (both directions); a seeded scheduler picks which pending delivery or which local edit to run next. At quiescence all clients and the server must hold the same document.

## Success criteria
- Literal hand-derived transform/compose/apply cases including tie rule.
- Randomised property tests (apply/compose/transform/invert) with a naive oracle.
- Convergence in seeded simulations with 2-5 clients; server log replay equals the document.
- Each rejection guard asserted by message; `mvn -q -B clean test` passes.
