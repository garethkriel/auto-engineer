# tcp-relay

- **Description:** `ae.net.TcpRelay`, a loopback TCP forwarding proxy over `ServerSocket`/`Socket`: per-client upstream connection, two pump threads, half-close propagation (`shutdownOutput`), idle timeout shared by both directions (poll via `SO_TIMEOUT` + shared `lastActivity`), connection cap with rejection, byte/connection stats, idempotent `close()`.
- **Language:** java
- **Tags:** networking, sockets, proxy, half-close, idle-timeout, concurrency
- **Source round:** practice/java/20261118T0256Z
- **Tests:** 12 JUnit 5 tests against in-process echo/scripted upstream servers (large payload, concurrent clients, half-close, timeouts, limit, shutdown).
