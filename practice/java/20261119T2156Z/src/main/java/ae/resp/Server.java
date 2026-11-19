package ae.resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-per-connection RESP server bound to the loopback interface. */
public final class Server implements AutoCloseable {
    private final Engine engine;
    private final ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;

    public Server(Engine engine, int port) throws IOException {
        this.engine = engine;
        this.server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        this.acceptor = new Thread(this::acceptLoop, "resp-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                clients.add(s);
                Thread t = new Thread(() -> serve(s), "resp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        Engine.Session session = engine.newSession();
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (true) {
                Resp.Value v;
                try {
                    v = Resp.parse(in);
                } catch (RespException e) {
                    protocolError(out, e.getMessage());
                    return;
                }
                if (v == null) {
                    return;
                }
                List<String> args = toArgs(v);
                if (args == null) {
                    protocolError(out, "expected array of bulk strings");
                    return;
                }
                out.write(Resp.encode(engine.execute(session, args)));
                if (in.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            // connection dropped or server closing
        } finally {
            clients.remove(socket);
        }
    }

    private static void protocolError(OutputStream out, String why) throws IOException {
        out.write(Resp.encode(new Resp.Err("ERR Protocol error: " + why.replace('\r', ' ').replace('\n', ' '))));
        out.flush();
    }

    private static List<String> toArgs(Resp.Value v) {
        if (!(v instanceof Resp.Arr arr) || arr.items() == null || arr.items().isEmpty()) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (Resp.Value item : arr.items()) {
            if (!(item instanceof Resp.Bulk b) || b.text() == null) {
                return null;
            }
            args.add(b.text());
        }
        return args;
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // ignore
        }
        for (Socket s : clients) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
        try {
            acceptor.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
