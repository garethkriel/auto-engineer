package ae.udp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReliableTransferTest {
    private static final InetAddress LOOP = InetAddress.getLoopbackAddress();
    private DatagramSocket recvSock;
    private DatagramSocket sendSock;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        recvSock = new DatagramSocket(0, LOOP);
        sendSock = new DatagramSocket(0, LOOP);
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        recvSock.close();
        sendSock.close();
    }

    private InetSocketAddress target() {
        return new InetSocketAddress(LOOP, recvSock.getLocalPort());
    }

    private Future<byte[]> startReceiver(Predicate<Integer> drop, long linger) {
        ReliableReceiver r = new ReliableReceiver(recvSock, drop, linger);
        return pool.submit(() -> r.receive(5000));
    }

    private ReliableSender sender(int chunk, int retries) {
        return new ReliableSender(sendSock, target(), chunk, 40, retries);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    void lossless_multiChunkMessageArrivesIntact() throws Exception {
        byte[] msg = randomBytes(1000);
        Future<byte[]> got = startReceiver(i -> false, 20);
        int sent = sender(300, 3).send(msg); // chunks: 300,300,300,100 = 4
        assertEquals(4, sent);
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
    }

    @Test
    void emptyMessageIsOneEmptyLastChunk() throws Exception {
        Future<byte[]> got = startReceiver(i -> false, 20);
        assertEquals(1, sender(100, 3).send(new byte[0]));
        assertEquals(0, got.get(5, TimeUnit.SECONDS).length);
    }

    @Test
    void messageExactlyOneChunkMultipleIsNotPaddedWithEmptyChunk() throws Exception {
        byte[] msg = randomBytes(200);
        Future<byte[]> got = startReceiver(i -> false, 20);
        assertEquals(2, sender(100, 3).send(msg));
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
    }

    @Test
    void lostDataPacketIsRetransmitted() throws Exception {
        byte[] msg = randomBytes(250); // 3 chunks of 100,100,50
        // drop the very first datagram and the 3rd received one
        Future<byte[]> got = startReceiver(i -> i == 0 || i == 2, 20);
        int sent = sender(100, 5).send(msg);
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
        // arrivals: c0(#0 dropped) c0(#1) c1(#2 dropped) c1(#3) c2(#4) => 5 transmissions
        assertEquals(5, sent);
    }

    @Test
    void lostAcksDoNotDeliverDuplicates() throws Exception {
        byte[] msg = randomBytes(300); // 3 chunks
        // drop ACK #0 and ACK #1 (indexes -1 and -2)
        Future<byte[]> got = startReceiver(i -> i == -1 || i == -2, 20);
        int sent = sender(100, 5).send(msg);
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
        // c0 sent, ack lost -> c0 again, ack#1 lost -> c0 again, ack#2 ok, c1, c2 => 5
        assertEquals(5, sent);
    }

    @Test
    void lostFinalAckIsReAckedDuringLinger() throws Exception {
        byte[] msg = randomBytes(150); // 2 chunks => 2 acks; drop the 2nd ack (index -2)
        Future<byte[]> got = startReceiver(i -> i == -2, 500);
        int sent = sender(100, 5).send(msg);
        assertEquals(3, sent); // last chunk had to be retransmitted
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
    }

    @Test
    void garbageDatagramsAreIgnoredByReceiverAndSender() throws Exception {
        byte[] msg = randomBytes(150);
        Future<byte[]> got = startReceiver(i -> false, 20);
        try (DatagramSocket noise = new DatagramSocket(0, LOOP)) {
            byte[] junk = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            noise.send(new DatagramPacket(junk, junk.length, target()));
            // stale/future ACKs and junk aimed at the sender's socket
            byte[] ackFar = Packet.ack(99).encode();
            noise.send(new DatagramPacket(ackFar, ackFar.length, LOOP, sendSock.getLocalPort()));
            noise.send(new DatagramPacket(junk, junk.length, LOOP, sendSock.getLocalPort()));
        }
        sender(100, 3).send(msg);
        assertArrayEquals(msg, got.get(5, TimeUnit.SECONDS));
    }

    @Test
    void outOfOrderFuturePacketIsNotAcknowledgedOrAccepted() throws Exception {
        Future<byte[]> got = startReceiver(i -> false, 20);
        byte[] future = Packet.data(5, true, new byte[] {7}).encode();
        sendSock.send(new DatagramPacket(future, future.length, target()));
        sendSock.setSoTimeout(150);
        DatagramPacket reply = new DatagramPacket(new byte[64], 64);
        assertThrows(SocketTimeoutException.class, () -> sendSock.receive(reply));
        // a correct transfer still works afterwards
        sender(100, 3).send(new byte[] {1});
        assertArrayEquals(new byte[] {1}, got.get(5, TimeUnit.SECONDS));
    }

    @Test
    void retriesExhaustedThrowsIoExceptionWithAttemptCount() {
        // nobody is listening: use a bound-but-silent socket
        ReliableSender s = new ReliableSender(sendSock, target(), 100, 20, 2);
        IOException e = assertThrows(IOException.class, () -> s.send(new byte[] {1}));
        assertTrue(e.getMessage().contains("no ack"), e.getMessage());
        // 1 initial send + 2 retries, all observable at the silent socket
        int seen = 0;
        try {
            recvSock.setSoTimeout(100);
            while (true) {
                recvSock.receive(new DatagramPacket(new byte[64], 64));
                seen++;
            }
        } catch (IOException done) {
            // timeout ends the drain
        }
        assertEquals(3, seen);
    }

    @Test
    void receiverTimesOutWhenNothingArrives() {
        ReliableReceiver r = new ReliableReceiver(recvSock, i -> false, 10);
        assertThrows(SocketTimeoutException.class, () -> r.receive(80));
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReliableSender(sendSock, target(), 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReliableSender(sendSock, target(), 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReliableSender(sendSock, target(), 10, 10, -1));
        assertThrows(NullPointerException.class, () -> new ReliableReceiver(null, i -> false, 0));
    }

    @Test
    void heavyLossStillDeliversWithEnoughRetries() throws Exception {
        byte[] msg = randomBytes(2000); // 20 chunks
        // drop every 2nd received datagram and every 3rd ACK
        Future<byte[]> got = startReceiver(i -> i >= 0 ? i % 2 == 1 : (-1 - i) % 3 == 2, 100);
        sender(100, 20).send(msg);
        assertArrayEquals(msg, got.get(10, TimeUnit.SECONDS));
    }
}
