# Task: TCP relay (level 15, networking)

Implement `ae.net.TcpRelay`: a TCP forwarding proxy built on `ServerSocket`/`Socket`.

Overlap check: `benchmark/java/` names are roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql. A byte-level connection relay with half-close and idle timeouts matches none (miniserver is a server, not a proxy; ratelimit is an algorithm).

## API
- `TcpRelay(int listenPort, InetSocketAddress upstream, Duration idleTimeout, int maxConnections)`; port 0 = ephemeral; idleTimeout > 0 and maxConnections >= 1, else `IllegalArgumentException`; nulls -> NPE.
- `start()` binds on loopback (idempotent-failing: second call throws `IllegalStateException`); `port()` returns the bound port (ISE before start).
- `stats()` returns an immutable record `Stats(accepted, rejected, active, upstreamConnectFailures, idleTimeouts, bytesToUpstream, bytesToClient)`.
- `close()` stops accepting, closes all live connections, waits for pump threads to finish; idempotent.

## Behaviour
1. Each accepted client gets its own upstream connection; bytes are copied both ways.
2. Half-close: client EOF -> `shutdownOutput` on upstream, the upstream->client direction keeps flowing; symmetric. A connection ends when both directions finished.
3. Upstream connect failure: client socket is closed, counter incremented, `accepted` still counts it.
4. Idle timeout: if no bytes move in either direction for `idleTimeout`, both sockets close and `idleTimeouts` is incremented once.
5. More than `maxConnections` concurrent connections: extra client is closed immediately, `rejected` incremented (not counted in `accepted`).
6. `active` returns to 0 once every connection has ended, whichever side closed.

## Success criteria
`mvn -q -B clean test` passes, tests run repeatedly (concurrency) without flakiness.
