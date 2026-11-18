package ae.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TcpRelayTest {

    /** Upstream test server: each connection is handled by the given handler on its own thread. */
    interface Handler {
        void handle(Socket s) throws IOException;
    }

    private final List<AutoCloseable> toClose = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private ServerSocket upstream(Handler h) throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        toClose.add(ss);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    toClose.add(s);
                    Thread w = new Thread(() -> {
                        try {
                            h.handle(s);
                        } catch (IOException ignored) {
                            // peer closed: fine for test servers
                        }
                    });
                    w.setDaemon(true);
                    w.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    private static void echo(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            out.flush();
        }
        s.shutdownOutput();
    }

    private TcpRelay relay(ServerSocket up, long idleMs, int max) throws IOException {
        TcpRelay r = new TcpRelay(0, new InetSocketAddress(InetAddress.getLoopbackAddress(), up.getLocalPort()),
                Duration.ofMillis(idleMs), max);
        r.start();
        toClose.add(r);
        return r;
    }

    private Socket client(TcpRelay r) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), r.port());
        s.setSoTimeout(10_000);
        toClose.add(s);
        return s;
    }

    private static byte[] readAll(Socket s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = s.getInputStream().read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readExactly(Socket s, int len) throws IOException {
        return s.getInputStream().readNBytes(len);
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void echoRoundTripAndByteCounters() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 5_000, 10);
        Socket c = client(r);
        c.getOutputStream().write("hello relay".getBytes(StandardCharsets.UTF_8));
        c.getOutputStream().flush();
        assertEquals("hello relay", new String(readExactly(c, 11), StandardCharsets.UTF_8));
        c.close();
        await(() -> r.stats().active() == 0, "active == 0");
        TcpRelay.Stats st = r.stats();
        assertEquals(1, st.accepted());
        assertEquals(0, st.rejected());
        assertEquals(11, st.bytesToUpstream());
        assertEquals(11, st.bytesToClient());
        assertEquals(0, st.idleTimeouts());
        assertEquals(0, st.upstreamConnectFailures());
    }

    @Test
    void largePayloadSurvivesIntact() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 5_000, 10);
        Socket c = client(r);
        byte[] data = new byte[1_000_003];
        new Random(42).nextBytes(data);
        Thread writer = new Thread(() -> {
            try {
                c.getOutputStream().write(data);
                c.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        byte[] back = readExactly(c, data.length);
        writer.join();
        assertArrayEquals(data, back);
        c.close();
        await(() -> r.stats().active() == 0, "active == 0");
        assertEquals(data.length, r.stats().bytesToUpstream());
        assertEquals(data.length, r.stats().bytesToClient());
    }

    @Test
    void clientHalfCloseStillDeliversUpstreamReply() throws Exception {
        // upstream reads until EOF, then answers: only works if EOF is forwarded as shutdownOutput
        ServerSocket up = upstream(s -> {
            byte[] all = s.getInputStream().readAllBytes();
            s.getOutputStream().write(("got:" + new String(all, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            s.shutdownOutput();
        });
        TcpRelay r = relay(up, 5_000, 10);
        Socket c = client(r);
        c.getOutputStream().write("abc".getBytes(StandardCharsets.UTF_8));
        c.shutdownOutput();
        assertEquals("got:abc", new String(readAll(c), StandardCharsets.UTF_8));
        await(() -> r.stats().active() == 0, "active == 0");
    }

    @Test
    void upstreamCloseFirstEndsClientStream() throws Exception {
        ServerSocket up = upstream(s -> {
            s.getOutputStream().write("bye".getBytes(StandardCharsets.UTF_8));
            s.close();
        });
        TcpRelay r = relay(up, 5_000, 10);
        Socket c = client(r);
        assertEquals("bye", new String(readAll(c), StandardCharsets.UTF_8));
        c.close();
        await(() -> r.stats().active() == 0, "active == 0");
    }

    @Test
    void upstreamDownClosesClientAndCountsFailure() throws Exception {
        ServerSocket dead = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int deadPort = dead.getLocalPort();
        dead.close(); // nobody listens now
        TcpRelay r = new TcpRelay(0, new InetSocketAddress(InetAddress.getLoopbackAddress(), deadPort),
                Duration.ofSeconds(5), 10);
        r.start();
        toClose.add(r);
        Socket c = client(r);
        assertEquals(-1, c.getInputStream().read());
        await(() -> r.stats().upstreamConnectFailures() == 1, "connect failure counted");
        await(() -> r.stats().active() == 0, "active == 0");
        assertEquals(1, r.stats().accepted());
    }

    @Test
    void silentConnectionIsClosedAfterIdleTimeoutExactlyOnce() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 200, 10);
        Socket c = client(r);
        long t0 = System.nanoTime();
        assertEquals(-1, c.getInputStream().read());
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertTrue(ms >= 150, "closed too early: " + ms + "ms");
        assertTrue(ms < 5_000, "closed too late: " + ms + "ms");
        await(() -> r.stats().active() == 0, "active == 0");
        assertEquals(1, r.stats().idleTimeouts());
    }

    @Test
    void trafficInEitherDirectionKeepsConnectionAlive() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 300, 10);
        Socket c = client(r);
        // 12 round trips, 60 ms apart = 720 ms total, well beyond the 300 ms idle limit
        for (int i = 0; i < 12; i++) {
            c.getOutputStream().write(i);
            c.getOutputStream().flush();
            assertEquals(i, c.getInputStream().read(), "round trip " + i);
            Thread.sleep(60);
        }
        assertEquals(0, r.stats().idleTimeouts());
        assertEquals(1, r.stats().active());
    }

    @Test
    void oneWayStreamingIsNotMistakenForIdleWhileOtherDirectionIsQuiet() throws Exception {
        // upstream sends a byte every 60ms and never reads; client sends nothing
        ServerSocket up = upstream(s -> {
            for (int i = 0; i < 10; i++) {
                s.getOutputStream().write(i);
                s.getOutputStream().flush();
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    return;
                }
            }
            s.shutdownOutput();
            s.getInputStream().readAllBytes();
        });
        TcpRelay r = relay(up, 300, 10);
        Socket c = client(r);
        for (int i = 0; i < 10; i++) {
            assertEquals(i, c.getInputStream().read());
        }
        assertEquals(0, r.stats().idleTimeouts());
    }

    @Test
    void connectionsOverLimitAreRejectedAndSlotIsReusable() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 5_000, 1);
        Socket first = client(r);
        first.getOutputStream().write(7);
        assertEquals(7, first.getInputStream().read());

        Socket second = client(r);
        assertEquals(-1, second.getInputStream().read());
        await(() -> r.stats().rejected() == 1, "rejected == 1");
        assertEquals(1, r.stats().accepted());
        assertEquals(1, r.stats().active());

        first.close();
        await(() -> r.stats().active() == 0, "slot freed");
        Socket third = client(r);
        third.getOutputStream().write(9);
        assertEquals(9, third.getInputStream().read());
        assertEquals(2, r.stats().accepted());
        assertEquals(1, r.stats().rejected());
    }

    @Test
    void closeTerminatesLiveConnectionsAndIsIdempotent() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 60_000, 10);
        Socket a = client(r);
        Socket b = client(r);
        a.getOutputStream().write(1);
        b.getOutputStream().write(2);
        assertEquals(1, a.getInputStream().read());
        assertEquals(2, b.getInputStream().read());
        assertEquals(2, r.stats().active());
        r.close();
        assertEquals(0, r.stats().active());
        assertEquals(-1, a.getInputStream().read());
        assertEquals(-1, b.getInputStream().read());
        r.close();
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), r.port()));
    }

    @Test
    void manyConcurrentClientsGetTheirOwnBytesBack() throws Exception {
        TcpRelay r = relay(upstream(TcpRelayTest::echo), 10_000, 50);
        int clients = 20;
        List<Thread> threads = new ArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < clients; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try (Socket c = client(r)) {
                    byte[] data = new byte[50_000 + id];
                    new Random(id).nextBytes(data);
                    Thread w = new Thread(() -> {
                        try {
                            c.getOutputStream().write(data);
                        } catch (IOException e) {
                            errors.add("write " + id + ": " + e);
                        }
                    });
                    w.start();
                    byte[] back = readExactly(c, data.length);
                    w.join();
                    if (!java.util.Arrays.equals(data, back)) {
                        errors.add("mismatch for client " + id);
                    }
                } catch (Exception e) {
                    errors.add("client " + id + ": " + e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(List.of(), errors);
        await(() -> r.stats().active() == 0, "active == 0");
        assertEquals(clients, r.stats().accepted());
        long expected = 0;
        for (int i = 0; i < clients; i++) {
            expected += 50_000 + i;
        }
        assertEquals(expected, r.stats().bytesToUpstream());
        assertEquals(expected, r.stats().bytesToClient());
    }

    @Test
    void lifecycleAndArgumentValidation() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);
        assertThrows(NullPointerException.class, () -> new TcpRelay(0, null, Duration.ofSeconds(1), 1));
        assertThrows(NullPointerException.class, () -> new TcpRelay(0, addr, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TcpRelay(0, addr, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new TcpRelay(0, addr, Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> new TcpRelay(0, addr, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new TcpRelay(-1, addr, Duration.ofSeconds(1), 1));
        TcpRelay r = new TcpRelay(0, addr, Duration.ofSeconds(1), 1);
        assertThrows(IllegalStateException.class, r::port);
        r.start();
        toClose.add(r);
        assertTrue(r.port() > 0);
        assertThrows(IllegalStateException.class, r::start);
    }
}
