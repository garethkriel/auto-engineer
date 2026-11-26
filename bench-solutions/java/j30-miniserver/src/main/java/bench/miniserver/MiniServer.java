package bench.miniserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small HTTP/1.1 server on raw sockets. */
public final class MiniServer implements AutoCloseable {
    private static final int MAX_LINE = 65536;

    private final int requestedPort;
    private final Handler handler;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private Thread acceptor;
    private boolean started;
    private volatile boolean closed;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("bad port or handler");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }

    public synchronized void start() throws IOException {
        if (closed || started) {
            throw new IllegalStateException("cannot start");
        }
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 256);
        started = true;
        acceptor = new Thread(this::acceptLoop, "miniserver-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public synchronized int port() {
        if (!started) {
            throw new IllegalStateException("not started");
        }
        return server.getLocalPort();
    }

    @Override
    public void close() {
        ServerSocket s;
        Thread a;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            s = server;
            a = acceptor;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Socket c : connections) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        if (a != null) {
            try {
                a.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // sweep connections accepted during shutdown
        for (Socket c : connections) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private void acceptLoop() {
        while (!closed) {
            Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (closed) return;
                continue;
            }
            connections.add(c);
            if (closed) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // closing
                }
                connections.remove(c);
                return;
            }
            Thread t = new Thread(() -> serve(c), "miniserver-conn");
            t.setDaemon(true);
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

    private void serve(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 8192);
            while (!closed) {
                Request req;
                boolean[] flags = new boolean[2]; // [0] http10, [1] unused
                try {
                    String line = readLine(in, true);
                    if (line == null) {
                        break; // clean EOF between requests
                    }
                    req = parseRequest(in, line, flags);
                } catch (Malformed m) {
                    writeResponse(out, 400, null, null, true);
                    finish(sock, in);
                    return;
                }
                boolean http10 = flags[0];
                boolean close = wantsClose(req.headers().get("connection"), http10);
                Response r = null;
                try {
                    r = handler.handle(req);
                } catch (Throwable t) {
                    r = null;
                }
                if (r == null || r.status() < 100 || r.status() > 599) {
                    writeResponse(out, 500, null, null, close);
                } else {
                    writeResponse(out, r.status(), r.headers(), r.body(), close);
                }
                if (close) {
                    finish(sock, in);
                    return;
                }
            }
        } catch (IOException e) {
            // connection broken or closed
        } finally {
            connections.remove(sock);
            try {
                sock.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    /** Half-closes, briefly drains unread input so the peer sees the response, then closes. */
    private static void finish(Socket sock, InputStream in) {
        try {
            sock.shutdownOutput();
            sock.setSoTimeout(300);
            long deadline = System.nanoTime() + 500_000_000L;
            byte[] buf = new byte[4096];
            int total = 0;
            while (System.nanoTime() < deadline && total < (1 << 20)) {
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
            }
        } catch (SocketTimeoutException ignored) {
            // fine
        } catch (IOException ignored) {
            // fine
        }
    }

    private static boolean wantsClose(String connection, boolean http10) {
        boolean close = false;
        boolean keep = false;
        if (connection != null) {
            for (String tok : connection.split(",")) {
                String t = tok.trim();
                if (t.equalsIgnoreCase("close")) close = true;
                if (t.equalsIgnoreCase("keep-alive")) keep = true;
            }
        }
        if (http10) {
            return close || !keep;
        }
        return close;
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

    private static void writeResponse(OutputStream out, int status, Map<String, String> headers, byte[] body,
                                      boolean close) throws IOException {
        byte[] b = body == null ? new byte[0] : body;
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                if (k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("connection")) continue;
                sb.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(b.length).append("\r\n");
        if (close) {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    // --------------------------------------------------------------- parsing

    /**
     * Reads a line terminated by LF (a CR before it is dropped). Returns null on EOF with no bytes
     * read when {@code eofOk}; an EOF in the middle of a line is malformed.
     */
    private static String readLine(InputStream in, boolean eofOk) throws IOException, Malformed {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int n = 0;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (n == 0 && eofOk) return null;
                throw new Malformed("eof in line");
            }
            if (c == '\n') break;
            buf.write(c);
            if (++n > MAX_LINE) throw new Malformed("line too long");
        }
        byte[] a = buf.toByteArray();
        int len = a.length;
        if (len > 0 && a[len - 1] == '\r') len--;
        return new String(a, 0, len, StandardCharsets.ISO_8859_1);
    }

    private static Request parseRequest(InputStream in, String line, boolean[] flags) throws IOException, Malformed {
        String[] parts = line.split(" ", -1);
        if (parts.length != 3) throw new Malformed("request line");
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (method.isEmpty()) throw new Malformed("method");
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') throw new Malformed("method");
        }
        if (target.isEmpty() || target.charAt(0) != '/') throw new Malformed("target");
        if (version.equals("HTTP/1.1")) {
            flags[0] = false;
        } else if (version.equals("HTTP/1.0")) {
            flags[0] = true;
        } else {
            throw new Malformed("version");
        }
        String path;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q < 0) {
            path = decode(target, false);
        } else {
            path = decode(target.substring(0, q), false);
            for (String piece : target.substring(q + 1).split("&")) {
                if (piece.isEmpty()) continue;
                int eq = piece.indexOf('=');
                String name = eq < 0 ? piece : piece.substring(0, eq);
                String value = eq < 0 ? "" : piece.substring(eq + 1);
                query.put(decode(name, true), decode(value, true));
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String h = readLine(in, false);
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) throw new Malformed("header");
            String name = h.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = trimOws(h.substring(colon + 1));
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        byte[] body = readBody(in, headers);
        return new Request(method, path, Collections.unmodifiableMap(query),
            Collections.unmodifiableMap(headers), body);
    }

    private static String trimOws(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException, Malformed {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in, false);
                int semi = sizeLine.indexOf(';');
                String hex = trimOws(semi < 0 ? sizeLine : sizeLine.substring(0, semi));
                if (hex.isEmpty() || hex.length() > 15) throw new Malformed("chunk size");
                long size = 0;
                for (int i = 0; i < hex.length(); i++) {
                    int d = Character.digit(hex.charAt(i), 16);
                    if (d < 0 || hex.charAt(i) > 'f') throw new Malformed("chunk size");
                    size = size * 16 + d;
                }
                if (size == 0) {
                    while (!readLine(in, false).isEmpty()) {
                        // trailers ignored
                    }
                    return body.toByteArray();
                }
                copy(in, body, size);
                int c = in.read();
                if (c == '\r') {
                    c = in.read();
                }
                if (c != '\n') throw new Malformed("chunk ending");
            }
        }
        String cl = headers.get("content-length");
        if (cl == null) return new byte[0];
        if (cl.isEmpty() || cl.length() > 18) throw new Malformed("content-length");
        long len = 0;
        for (int i = 0; i < cl.length(); i++) {
            char c = cl.charAt(i);
            if (c < '0' || c > '9') throw new Malformed("content-length");
            len = len * 10 + (c - '0');
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream((int) Math.min(len, 1 << 16));
        copy(in, body, len);
        return body.toByteArray();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, long n) throws IOException, Malformed {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new Malformed("eof in body");
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static String decode(String s, boolean plusIsSpace) throws Malformed {
        if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) {
            return fromLatin1AsUtf8(s);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new Malformed("bad escape");
                }
                int h = Character.digit(s.charAt(i + 1), 16);
                int l = Character.digit(s.charAt(i + 2), 16);
                if (h < 0 || l < 0 || s.charAt(i + 1) > 'f' || s.charAt(i + 2) > 'f') throw new Malformed("bad escape");
                out.write(h * 16 + l);
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
            } else {
                out.write(c);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String fromLatin1AsUtf8(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
        }
        return s;
    }
}
