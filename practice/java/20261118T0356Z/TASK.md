# Task 20261118T0356Z — Stop-and-wait reliable transfer over UDP (level 15, sockets)

Overlap check: benchmark names (miniserver, ratelimit, kvlog, cache, queue...) are servers/algorithms over TCP/HTTP or in-memory; a UDP datagram ARQ protocol matches none. Previous rounds covered HttpClient retries and a TCP relay; this one uses `DatagramSocket`.

## Requirements
Package `ae.udp`, standard library only.
- `Packet` (record-like value): `DATA(seq, last, payload)` and `ACK(seq)`; `encode()`/`Packet.decode(byte[],int)` with layout `[magic 0xAE][type][seq int32][flags][payload...]`; decode returns `Optional.empty()` for garbage (wrong magic, short, unknown type, ACK with payload).
- `ReliableReceiver(DatagramSocket, Predicate<Integer> dropPolicy, long lingerMillis)`: `byte[] receive(long timeoutMillis)` assembles one message. ACKs each in-order DATA, re-ACKs duplicates (without re-delivering), ignores garbage and out-of-order future packets. After the last packet it lingers for `lingerMillis` re-ACKing duplicates of the final packet. `dropPolicy` is called with the 0-based index of each received datagram (true => pretend lost) and with `-1 - n` for the n-th ACK about to be sent (true => ACK not sent); used for deterministic fault injection. Throws `SocketTimeoutException` if no complete message arrives in time.
- `ReliableSender(DatagramSocket, SocketAddress, int chunkSize, long ackTimeoutMillis, int maxRetries)`: `send(byte[])` splits into chunks (empty message = one empty last chunk), sends each and waits for matching ACK, retransmitting up to `maxRetries` times; ignores garbage and stale ACKs; throws `IOException` ("no ack") when retries are exhausted. Returns the total number of datagrams transmitted.
- Success: `mvn -q -B test` green; tests cover lossless, empty, multi-chunk, lost data, lost ACKs (no duplicate delivery), lost final ACK (linger), garbage ignored, retries exhausted, packet codec.
