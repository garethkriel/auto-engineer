# udp-stop-and-wait

- **Description:** Reliable message transfer over UDP (`DatagramSocket`): 7-byte-header codec (`Packet`), stop-and-wait `ReliableSender` with retransmit/timeout/retry limit, `ReliableReceiver` that dedupes, re-ACKs, ignores garbage/future packets and lingers to re-ACK a lost final ACK. Receiver takes an index-based drop predicate for deterministic loss tests.
- **Language:** java
- **Tags:** networking, udp, datagram, arq, retransmit, fault-injection
- **Source round:** practice/java/20261118T0356Z
- **Tests:** `mvn -q -B test` (17 tests)
