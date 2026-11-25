package bench.miniserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small HTTP/1.1 server on raw sockets, one thread per connection. */
public final class MiniServer implements AutoCloseable {

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }

    private static final int MAX_LINE = 1 << 20;
    private static final int MAX_HEADERS = 10_000;

    private final int requestedPort;
    private final Handler handler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;
    private volatile int boundPort = -1;
    private volatile boolean closed;
    private boolean started;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("bad port: " + port);
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        if (started) {
            return;
        }
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 256);
        } catch (IOException | RuntimeException e) {
            ss.close();
            throw e;
        }
        server = ss;
        boundPort = ss.getLocalPort();
        started = true;
        Thread t = new Thread(() -> acceptLoop(ss), "miniserver-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("not started");
        }
        return boundPort;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        ServerSocket ss = server;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!closed) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (closed || ss.isClosed()) {
                    return;
                }
                continue;
            }
            sockets.add(s);
            if (closed) {
                sockets.remove(s);
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
                return;
            }
            Thread t = new Thread(() -> serve(s), "miniserver-conn");
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    // ------------------------------------------------------------ connection

    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    private static final class Reader {
        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private int pos;
        private int lim;

        Reader(InputStream in) {
            this.in = in;
        }

        int read() throws IOException {
            if (pos >= lim) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) {
                    return -1;
                }
                pos = 0;
                lim = n;
            }
            return buf[pos++] & 0xff;
        }

        /** Reads a line without its terminator; null on EOF before any byte; Malformed on EOF mid-line. */
        String line() throws IOException, Malformed {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
            int c;
            boolean any = false;
            while ((c = read()) >= 0) {
                any = true;
                if (c == '\n') {
                    byte[] a = bo.toByteArray();
                    int len = a.length;
                    if (len > 0 && a[len - 1] == '\r') {
                        len--;
                    }
                    return new String(a, 0, len, StandardCharsets.UTF_8);
                }
                bo.write(c);
                if (bo.size() > MAX_LINE) {
                    throw new Malformed("line too long");
                }
            }
            if (any) {
                throw new Malformed("eof in line");
            }
            return null;
        }

        byte[] bytes(long n) throws IOException, Malformed {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(n, 65536));
            long left = n;
            while (left > 0) {
                if (pos >= lim) {
                    int k = in.read(buf, 0, buf.length);
                    if (k <= 0) {
                        throw new Malformed("eof in body");
                    }
                    pos = 0;
                    lim = k;
                }
                int take = (int) Math.min(left, lim - pos);
                bo.write(buf, pos, take);
                pos += take;
                left -= take;
            }
            return bo.toByteArray();
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            Reader rd = new Reader(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (!closed) {
                Request req;
                boolean http10;
                try {
                    String first = rd.line();
                    if (first == null) {
                        return;
                    }
                    String[] parts = first.split(" ", -1);
                    if (parts.length != 3 || !parts[0].matches("[A-Z]+") || !parts[1].startsWith("/")
                        || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
                        throw new Malformed("bad request line");
                    }
                    http10 = parts[2].equals("HTTP/1.0");
                    Map<String, String> headers = new LinkedHashMap<>();
                    int count = 0;
                    while (true) {
                        String h = rd.line();
                        if (h == null) {
                            throw new Malformed("eof in headers");
                        }
                        if (h.isEmpty()) {
                            break;
                        }
                        if (++count > MAX_HEADERS) {
                            throw new Malformed("too many headers");
                        }
                        int colon = h.indexOf(':');
                        if (colon < 0 || trim(h.substring(0, colon)).isEmpty()) {
                            throw new Malformed("bad header");
                        }
                        String name = trim(h.substring(0, colon)).toLowerCase(java.util.Locale.ROOT);
                        String value = trim(h.substring(colon + 1));
                        headers.merge(name, value, (a, b) -> a + ", " + b);
                    }
                    String target = parts[1];
                    String rawPath = target;
                    String rawQuery = null;
                    int q = target.indexOf('?');
                    if (q >= 0) {
                        rawPath = target.substring(0, q);
                        rawQuery = target.substring(q + 1);
                    }
                    String path = decode(rawPath, false);
                    Map<String, String> query = new LinkedHashMap<>();
                    if (rawQuery != null) {
                        for (String piece : rawQuery.split("&")) {
                            if (piece.isEmpty()) {
                                continue;
                            }
                            int eq = piece.indexOf('=');
                            String k = eq < 0 ? piece : piece.substring(0, eq);
                            String v = eq < 0 ? "" : piece.substring(eq + 1);
                            query.put(decode(k, true), decode(v, true));
                        }
                    }
                    byte[] body = readBody(rd, headers);
                    req = new Request(parts[0], path, Collections.unmodifiableMap(query),
                        Collections.unmodifiableMap(headers), body);
                } catch (Malformed m) {
                    writeAndClose(s, out, 400, "Bad Request", null, new byte[0]);
                    return;
                }
                String conn = req.headers().getOrDefault("connection", "").toLowerCase(java.util.Locale.ROOT);
                boolean hasClose = hasToken(conn, "close");
                boolean keep = http10 ? hasToken(conn, "keep-alive") && !hasClose : !hasClose;
                int status = 500;
                Map<String, String> rh = null;
                byte[] rb = new byte[0];
                try {
                    Response resp = handler.handle(req);
                    if (resp != null && resp.status() >= 100 && resp.status() <= 599) {
                        status = resp.status();
                        rh = resp.headers();
                        rb = resp.body() == null ? new byte[0] : resp.body();
                    }
                } catch (Throwable t) {
                    status = 500;
                    rh = null;
                    rb = new byte[0];
                }
                if (closed) {
                    return;
                }
                byte[] wire = render(status, rh, rb, keep ? (http10 ? "keep-alive" : null) : "close");
                out.write(wire);
                out.flush();
                if (!keep) {
                    gracefulClose(s);
                    return;
                }
            }
        } catch (IOException ignored) {
            // connection broke or server closing
        } finally {
            sockets.remove(s);
            threads.remove(Thread.currentThread());
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private static boolean hasToken(String value, String token) {
        for (String t : value.split(",")) {
            if (t.trim().equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
            b--;
        }
        return s.substring(a, b);
    }

    private static byte[] readBody(Reader rd, Map<String, String> headers) throws IOException, Malformed {
        String te = headers.get("transfer-encoding");
        if (te != null && hasToken(te.toLowerCase(java.util.Locale.ROOT), "chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = rd.line();
                if (sizeLine == null) {
                    throw new Malformed("eof in chunk size");
                }
                int semi = sizeLine.indexOf(';');
                String hex = trim(semi >= 0 ? sizeLine.substring(0, semi) : sizeLine);
                if (hex.isEmpty() || hex.length() > 8) {
                    throw new Malformed("bad chunk size");
                }
                long size = 0;
                for (int i = 0; i < hex.length(); i++) {
                    int d = hex(hex.charAt(i));
                    if (d < 0) {
                        throw new Malformed("bad chunk size");
                    }
                    size = size * 16 + d;
                }
                if (size == 0) {
                    while (true) {
                        String t = rd.line();
                        if (t == null) {
                            throw new Malformed("eof in trailer");
                        }
                        if (t.isEmpty()) {
                            break;
                        }
                    }
                    return body.toByteArray();
                }
                body.write(rd.bytes(size));
                int c = rd.read();
                if (c == '\r') {
                    c = rd.read();
                }
                if (c != '\n') {
                    throw new Malformed("bad chunk ending");
                }
            }
        }
        String cl = headers.get("content-length");
        if (cl == null) {
            return new byte[0];
        }
        if (cl.isEmpty() || cl.length() > 10) {
            throw new Malformed("bad content-length");
        }
        long n = 0;
        for (int i = 0; i < cl.length(); i++) {
            char ch = cl.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new Malformed("bad content-length");
            }
            n = n * 10 + (ch - '0');
        }
        if (n > Integer.MAX_VALUE - 8) {
            throw new Malformed("body too large");
        }
        return rd.bytes(n);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String decode(String s, boolean plusIsSpace) throws Malformed {
        if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) {
            return s;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new Malformed("bad escape");
                }
                int h = hex(s.charAt(i + 1));
                int l = hex(s.charAt(i + 2));
                if (h < 0 || l < 0) {
                    throw new Malformed("bad escape");
                }
                bo.write(h * 16 + l);
                i += 3;
            } else if (c == '+' && plusIsSpace) {
                bo.write(' ');
                i++;
            } else {
                int cp = s.codePointAt(i);
                byte[] enc = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bo.write(enc, 0, enc.length);
                i += Character.charCount(cp);
            }
        }
        return bo.toString(StandardCharsets.UTF_8);
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static String clean(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
    }

    private static byte[] render(int status, Map<String, String> headers, byte[] body, String connection) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null || k.isEmpty()) {
                    continue;
                }
                if (k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("connection")) {
                    continue;
                }
                sb.append(clean(k)).append(": ").append(clean(v)).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (connection != null) {
            sb.append("Connection: ").append(connection).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private void writeAndClose(Socket s, OutputStream out, int status, String reasonText, Map<String, String> h,
                               byte[] body) throws IOException {
        Objects.requireNonNull(reasonText);
        out.write(render(status, h, body, "close"));
        out.flush();
        gracefulClose(s);
    }

    /** Half-closes, then drains input briefly so the peer sees our reply instead of a reset. */
    private void gracefulClose(Socket s) {
        try {
            s.shutdownOutput();
            s.setSoTimeout(300);
            long deadline = System.nanoTime() + 1_000_000_000L;
            byte[] junk = new byte[4096];
            long total = 0;
            InputStream in = s.getInputStream();
            while (!closed && System.nanoTime() < deadline && total < (1 << 20)) {
                int n = in.read(junk);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException ignored) {
            // timeout or reset: just close
        }
    }
}
