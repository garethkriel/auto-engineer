# resp-mini-server

- **Language:** java (standard library only)
- **Tags:** resp, redis, append-only-file, expiry, transactions, sockets, differential-testing, crash-recovery
- **Source round:** practice/java/20261119T2156Z

RESP2 codec (`Resp`), a Redis-like command engine (`Engine`: strings, lists, TTL, MULTI/EXEC, AOF with replay, truncated-tail repair and atomic `rewrite()`), and a loopback thread-per-connection `Server`.

Reusable ideas:
- Log *deterministic* commands: relative TTLs become `PXAT`, and lazy expiry is logged as `DEL`, so replay with frozen time reproduces state. Purge-and-log at open.
- `RespException.truncated()` separates "input ended mid-record" (repair by truncating) from garbage (fail).
- Clock is a `LongSupplier`; one clock reading per command/transaction.
- Differential `ModelTest` against a naive map model with random ops, expiry and reopen-from-log, plus a mutation kill-check (removing the lazy-expiry DEL log fails it).

Run: `mvn -q -B test`.
