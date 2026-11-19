package ae.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerTest {
    static Resp.Value call(Socket s, String... a) throws IOException {
        s.getOutputStream().write(Resp.encode(Resp.command(a)));
        s.getOutputStream().flush();
        return Resp.parse(s.getInputStream());
    }

    @Test
    void twoClientsShareStateButNotTransactions() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        try (Server srv = new Server(e, 0);
             Socket a = new Socket("127.0.0.1", srv.port());
             Socket b = new Socket("127.0.0.1", srv.port())) {
            a.setSoTimeout(5000);
            b.setSoTimeout(5000);
            assertEquals(new Resp.Simple("OK"), call(a, "SET", "k", "1"));
            assertEquals(new Resp.Bulk("1"), call(b, "GET", "k"));
            assertEquals(new Resp.Simple("OK"), call(a, "MULTI"));
            assertEquals(new Resp.Simple("QUEUED"), call(a, "SET", "k", "2"));
            assertEquals(new Resp.Bulk("1"), call(b, "GET", "k"));
            assertEquals(new Resp.Arr(List.of(new Resp.Simple("OK"))), call(a, "EXEC"));
            assertEquals(new Resp.Bulk("2"), call(b, "GET", "k"));
        }
    }

    @Test
    void pipelinedRequestsAnsweredInOrder() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        try (Server srv = new Server(e, 0); Socket a = new Socket("127.0.0.1", srv.port())) {
            a.setSoTimeout(5000);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int n = 300;
            for (int i = 0; i < n; i++) bos.write(Resp.encode(Resp.command("INCR", "c")));
            // one write split in awkward chunks to exercise partial reads
            byte[] all = bos.toByteArray();
            OutputStream out = a.getOutputStream();
            for (int off = 0; off < all.length; off += 7) {
                out.write(all, off, Math.min(7, all.length - off));
                out.flush();
            }
            InputStream in = a.getInputStream();
            for (int i = 1; i <= n; i++) {
                assertEquals(new Resp.Int(i), Resp.parse(in), "reply " + i);
            }
        }
    }

    @Test
    void protocolErrorGetsReplyThenClose() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        try (Server srv = new Server(e, 0); Socket a = new Socket("127.0.0.1", srv.port())) {
            a.setSoTimeout(5000);
            a.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.UTF_8));
            a.getOutputStream().flush();
            Resp.Value v = Resp.parse(a.getInputStream());
            Resp.Err err = assertInstanceOf(Resp.Err.class, v);
            assertTrue(err.message().startsWith("ERR Protocol error"), err.message());
            assertNull(Resp.parse(a.getInputStream()), "server closes after protocol error");
        }
    }

    @Test
    void nonArrayOrNonBulkRequestIsProtocolError() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        try (Server srv = new Server(e, 0); Socket a = new Socket("127.0.0.1", srv.port())) {
            a.setSoTimeout(5000);
            a.getOutputStream().write("*1\r\n:5\r\n".getBytes(StandardCharsets.UTF_8));
            a.getOutputStream().flush();
            assertInstanceOf(Resp.Err.class, Resp.parse(a.getInputStream()));
            assertNull(Resp.parse(a.getInputStream()));
        }
    }

    @Test
    void manyConcurrentClientsIncrementAtomically() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        int clients = 8, each = 200;
        try (Server srv = new Server(e, 0)) {
            List<Thread> ts = new ArrayList<>();
            List<Throwable> errs = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int c = 0; c < clients; c++) {
                Thread t = new Thread(() -> {
                    try (Socket s = new Socket("127.0.0.1", srv.port())) {
                        s.setSoTimeout(5000);
                        for (int i = 0; i < each; i++) call(s, "INCR", "n");
                    } catch (Throwable ex) {
                        errs.add(ex);
                    }
                });
                ts.add(t);
                t.start();
            }
            for (Thread t : ts) t.join(10_000);
            assertTrue(errs.isEmpty(), errs.toString());
            try (Socket s = new Socket("127.0.0.1", srv.port())) {
                s.setSoTimeout(5000);
                assertEquals(new Resp.Bulk("" + clients * each), call(s, "GET", "n"));
            }
        }
    }

    @Test
    void closeStopsAcceptingAndDropsClients() throws Exception {
        Engine e = new Engine(System::currentTimeMillis, null);
        Server srv = new Server(e, 0);
        int port = srv.port();
        Socket a = new Socket("127.0.0.1", port);
        a.setSoTimeout(5000);
        assertEquals(new Resp.Simple("PONG"), call(a, "PING"));
        srv.close();
        assertNull(Resp.parse(a.getInputStream()), "client sees EOF after server close");
        a.close();
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }
}
