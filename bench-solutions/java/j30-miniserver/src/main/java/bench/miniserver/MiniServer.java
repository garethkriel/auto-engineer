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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal HTTP/1.1 server on raw sockets, one thread per connection. */
public final class MiniServer implements AutoCloseable {

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }

    private static final class Malformed extends Exception {
        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    private static final int MAX_LINE = 65536;

    private final int requestedPort;
    private final Handler handler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;
    private volatile boolean started;
    private volatile boolean closed;
    private Thread acceptor;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("bad port");
        if (handler == null) throw new IllegalArgumentException("handler is null");
        this.requestedPort = port;
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (started || closed) throw new IllegalStateException("already started or closed");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 128);
        } catch (IOException | RuntimeException e) {
            ss.close();
            throw e;
        }
        server = ss;
        started = true;
        acceptor = new Thread(() -> acceptLoop(ss), "miniserver-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        ServerSocket ss = server;
        if (ss == null) throw new IllegalStateException("not started");
        return ss.getLocalPort();
    }

    @Override
    public void close() {
        ServerSocket ss;
        synchronized (this) {
            if (closed) return;
            closed = true;
            ss = server;
        }
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
        sockets.clear();
        Thread a = acceptor;
        if (a != null && a != Thread.currentThread()) {
            try {
                a.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!closed) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return;
            }
            sockets.add(s);
            if (closed) {
                closeQuietly(s);
                sockets.remove(s);
                return;
            }
            Thread t = new Thread(() -> serve(s), "miniserver-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 8192);
            while (!closed) {
                Request req;
                boolean http10;
                boolean keep;
                try {
                    String line = readLine(in);
                    if (line == null) break; // clean EOF between requests
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
                    if (!target.startsWith("/")) throw new Malformed("target");
                    if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) throw new Malformed("version");
                    http10 = version.equals("HTTP/1.0");

                    Map<String, String> headers = new LinkedHashMap<>();
                    while (true) {
                        String h = readLine(in);
                        if (h == null) throw new Malformed("eof in headers");
                        if (h.isEmpty()) break;
                        int colon = h.indexOf(':');
                        if (colon <= 0) throw new Malformed("header");
                        String name = h.substring(0, colon).toLowerCase(Locale.ROOT);
                        String value = strip(h.substring(colon + 1));
                        headers.merge(name, value, (a, b) -> a + ", " + b);
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
                            String k = eq < 0 ? piece : piece.substring(0, eq);
                            String v = eq < 0 ? "" : piece.substring(eq + 1);
                            query.put(decode(k, true), decode(v, true));
                        }
                    }

                    byte[] body = readBody(in, headers);
                    String conn = headers.get("connection");
                    boolean hasClose = hasToken(conn, "close");
                    boolean hasKeep = hasToken(conn, "keep-alive");
                    keep = http10 ? hasKeep && !hasClose : !hasClose;
                    req = new Request(method, path, Collections.unmodifiableMap(query),
                            Collections.unmodifiableMap(headers), body);
                } catch (Malformed m) {
                    writeResponse(out, 400, null, new byte[0], true);
                    gracefulClose(s, in);
                    return;
                }

                Response resp = null;
                try {
                    resp = handler.handle(req);
                } catch (Throwable t) {
                    resp = null;
                }
                int status = 500;
                Map<String, String> rh = null;
                byte[] rb = new byte[0];
                if (resp != null && resp.status() >= 100 && resp.status() <= 599) {
                    status = resp.status();
                    rh = resp.headers();
                    if (resp.body() != null) rb = resp.body();
                }
                writeResponse(out, status, rh, rb, !keep);
                if (!keep) {
                    gracefulClose(s, in);
                    return;
                }
            }
        } catch (IOException e) {
            // connection broken or closed by server
        } finally {
            closeQuietly(s);
            sockets.remove(s);
        }
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) return false;
        for (String t : value.split(",")) {
            if (t.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }

    /** Reads a line ended by LF (CR before it removed). Returns null on EOF with no bytes read. */
    private static String readLine(InputStream in) throws IOException, Malformed {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (buf.size() == 0) return null;
                throw new Malformed("eof in line");
            }
            if (c == '\n') break;
            buf.write(c);
            if (buf.size() > MAX_LINE) throw new Malformed("line too long");
        }
        byte[] b = buf.toByteArray();
        int n = b.length;
        if (n > 0 && b[n - 1] == '\r') n--;
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException, Malformed {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String line = readLine(in);
                if (line == null) throw new Malformed("eof in chunk");
                int semi = line.indexOf(';');
                String hex = strip(semi < 0 ? line : line.substring(0, semi));
                if (hex.isEmpty() || hex.length() > 8) throw new Malformed("chunk size");
                long size = 0;
                for (int i = 0; i < hex.length(); i++) {
                    int d = Character.digit(hex.charAt(i), 16);
                    if (d < 0 || hex.charAt(i) > 127) throw new Malformed("chunk size");
                    size = size * 16 + d;
                }
                if (size > Integer.MAX_VALUE - 8) throw new Malformed("chunk too large");
                if (size == 0) break;
                byte[] data = in.readNBytes((int) size);
                if (data.length < size) throw new Malformed("eof in chunk data");
                body.write(data);
                int c = in.read();
                if (c == '\r') {
                    c = in.read();
                }
                if (c != '\n') throw new Malformed("chunk ending");
            }
            while (true) {
                String t = readLine(in);
                if (t == null) throw new Malformed("eof in trailer");
                if (t.isEmpty()) break;
            }
            return body.toByteArray();
        }
        String cl = headers.get("content-length");
        if (cl == null) return new byte[0];
        if (cl.isEmpty() || cl.length() > 10) throw new Malformed("content-length");
        long n = 0;
        for (int i = 0; i < cl.length(); i++) {
            char c = cl.charAt(i);
            if (c < '0' || c > '9') throw new Malformed("content-length");
            n = n * 10 + (c - '0');
        }
        if (n > Integer.MAX_VALUE - 8) throw new Malformed("content-length");
        byte[] body = in.readNBytes((int) n);
        if (body.length < n) throw new Malformed("eof in body");
        return body;
    }

    private static String decode(String s, boolean plusIsSpace) throws Malformed {
        if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) throw new Malformed("escape");
                int h = Character.digit(s.charAt(i + 1), 16);
                int l = Character.digit(s.charAt(i + 2), 16);
                if (h < 0 || l < 0 || s.charAt(i + 1) > 127 || s.charAt(i + 2) > 127) throw new Malformed("escape");
                out.write(h * 16 + l);
                i += 3;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
                i++;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
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

    private static void writeResponse(OutputStream out, int status, Map<String, String> headers,
                                      byte[] body, boolean close) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                if (k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("connection")) continue;
                sb.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (close) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Half-closes, briefly drains unread input so the peer sees the response rather than a reset, then closes. */
    private static void gracefulClose(Socket s, InputStream in) {
        try {
            s.shutdownOutput();
            s.setSoTimeout(200);
            long deadline = System.nanoTime() + 300_000_000L;
            byte[] buf = new byte[4096];
            int total = 0;
            while (System.nanoTime() < deadline && total < (1 << 20)) {
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
            }
        } catch (IOException ignored) {
            // timeout or reset
        }
    }
}
