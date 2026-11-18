package ae.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A TCP forwarding proxy: every client connection gets its own upstream connection and bytes are
 * copied in both directions. Supports half-close, an idle timeout shared by both directions and a
 * cap on concurrent connections. Listens on the loopback interface only.
 */
public final class TcpRelay implements AutoCloseable {

    /** Immutable snapshot of the relay's counters. */
    public record Stats(long accepted, long rejected, int active, long upstreamConnectFailures,
                        long idleTimeouts, long bytesToUpstream, long bytesToClient) { }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int BUFFER_SIZE = 8192;

    private final int listenPort;
    private final InetSocketAddress upstream;
    private final long idleNanos;
    private final int pollMillis;
    private final int maxConnections;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong connectFailures = new AtomicLong();
    private final AtomicLong idleTimeouts = new AtomicLong();
    private final AtomicLong toUpstream = new AtomicLong();
    private final AtomicLong toClient = new AtomicLong();

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-relay");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocket server;
    private Thread acceptThread;

    public TcpRelay(int listenPort, InetSocketAddress upstream, Duration idleTimeout, int maxConnections) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (listenPort < 0 || listenPort > 65535) {
            throw new IllegalArgumentException("listenPort out of range: " + listenPort);
        }
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1");
        }
        this.listenPort = listenPort;
        this.upstream = upstream;
        this.idleNanos = idleTimeout.toNanos();
        // poll often enough to notice idleness promptly, without spinning
        this.pollMillis = (int) Math.max(10, Math.min(1_000, idleTimeout.toMillis() / 4));
        this.maxConnections = maxConnections;
    }

    /** Binds the listener and starts accepting. */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        server = new ServerSocket(listenPort, 50, InetAddress.getLoopbackAddress());
        acceptThread = new Thread(this::acceptLoop, "tcp-relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** The bound port. */
    public int port() {
        ServerSocket s = server;
        if (s == null) {
            throw new IllegalStateException("not started");
        }
        return s.getLocalPort();
    }

    public Stats stats() {
        return new Stats(accepted.get(), rejected.get(), active.get(), connectFailures.get(),
                idleTimeouts.get(), toUpstream.get(), toClient.get());
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return; // server socket closed
            }
            // only this thread increments, so check-then-increment cannot overshoot the limit
            if (active.get() >= maxConnections) {
                rejected.incrementAndGet();
                closeQuietly(client);
                continue;
            }
            active.incrementAndGet();
            accepted.incrementAndGet();
            Connection c = new Connection(client);
            connections.add(c);
            try {
                pool.execute(c::run);
            } catch (RuntimeException e) { // pool already shut down
                c.finish();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ServerSocket s = server;
        if (s != null) {
            closeQuietly(s);
            try {
                acceptThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Connection c : connections) {
            c.closeSockets();
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // nothing useful to do
        }
    }

    private final class Connection {
        private final Socket client;
        private volatile Socket up;
        private volatile long lastActivity = System.nanoTime();
        private final AtomicInteger pumpsLeft = new AtomicInteger(2);
        private final AtomicBoolean idleCounted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        Connection(Socket client) {
            this.client = client;
        }

        void run() {
            Socket u = new Socket();
            try {
                u.connect(upstream, CONNECT_TIMEOUT_MS);
                u.setSoTimeout(pollMillis);
                client.setSoTimeout(pollMillis);
                up = u;
            } catch (IOException e) {
                connectFailures.incrementAndGet();
                closeQuietly(u);
                finish();
                return;
            }
            if (closed.get()) {
                finish();
                return;
            }
            try {
                pool.execute(() -> pump(up, client, toClient));
            } catch (RuntimeException e) {
                finish();
                return;
            }
            pump(client, up, toUpstream);
        }

        /** Copies from {@code from} to {@code to} until EOF, error or idle timeout. */
        private void pump(Socket from, Socket to, AtomicLong counter) {
            byte[] buf = new byte[BUFFER_SIZE];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                while (true) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (SocketTimeoutException e) {
                        if (System.nanoTime() - lastActivity >= idleNanos) {
                            if (idleCounted.compareAndSet(false, true)) {
                                idleTimeouts.incrementAndGet();
                            }
                            closeSockets();
                            return;
                        }
                        continue;
                    }
                    if (n < 0) {
                        try {
                            to.shutdownOutput(); // propagate EOF, keep the other direction open
                        } catch (IOException ignored) {
                            closeSockets();
                        }
                        return;
                    }
                    lastActivity = System.nanoTime();
                    out.write(buf, 0, n);
                    out.flush();
                    counter.addAndGet(n);
                }
            } catch (IOException e) {
                closeSockets();
            } finally {
                if (pumpsLeft.decrementAndGet() == 0) {
                    finish();
                }
            }
        }

        void closeSockets() {
            closeQuietly(client);
            Socket u = up;
            if (u != null) {
                closeQuietly(u);
            }
        }

        /** Idempotent teardown: closes sockets, frees the slot. */
        void finish() {
            if (finished.compareAndSet(false, true)) {
                closeSockets();
                connections.remove(this);
                active.decrementAndGet();
            }
        }
    }
}
