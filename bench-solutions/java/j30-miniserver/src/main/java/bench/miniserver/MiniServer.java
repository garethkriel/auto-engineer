package bench.miniserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A small HTTP/1.1 server on raw sockets. */
public final class MiniServer implements AutoCloseable {

    private static final int MAX_LINE = 1 << 20;

    private final int requestedPort;
    private final Handler handler;
    private final Object lock = new Object();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private boolean started;
    private boolean closed;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("bad port or handler");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    public void start() throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            if (started) {
                throw new IllegalStateException("already started");
            }
            ServerSocket ss = new ServerSocket(requestedPort, 256, InetAddress.getLoopbackAddress());
            serverSocket = ss;
            started = true;
            Thread t = new Thread(() -> acceptLoop(ss), "miniserver-accept");
            t.setDaemon(true);
            acceptThread = t;
            t.start();
        }
    }

    public int port() {
        synchronized (lock) {
            if (!started) {
                throw new IllegalStateException("not started");
            }
            return serverSocket.getLocalPort();
        }
    }

    @Override
    public void close() {
        ServerSocket ss;
        Thread at;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            ss = serverSocket;
            at = acceptThread;
        }
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                // ignore
            }
        }
        if (at != null) {
            try {
                at.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < 3; i++) {
            for (Socket s : connections) {
                closeQuietly(s);
            }
            connections.clear();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (true) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return;
            }
            boolean reject;
            synchronized (lock) {
                reject = closed;
                if (!reject) {
                    connections.add(s);
                }
            }
            if (reject) {
                closeQuietly(s);
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
        } catch (IOException e) {
            // ignore
        }
    }

    // ------------------------------------------------------------------

    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();
            while (!socket.isClosed()) {
                Request req;
                boolean keepAlive;
                String version;
                try {
                    byte[] first = readLine(in, true);
                    if (first == null) {
                        return; // clean EOF between requests
                    }
                    Object[] parsed = parseRequest(first, in);
                    req = (Request) parsed[0];
                    version = (String) parsed[1];
                } catch (Malformed m) {
                    try {
                        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (IOException e) {
                        // ignore
                    }
                    return;
                }
                boolean http10 = version.equals("HTTP/1.0");
                String conn = req.headers().get("connection");
                boolean hasClose = hasToken(conn, "close");
                boolean hasKeep = hasToken(conn, "keep-alive");
                keepAlive = http10 ? (hasKeep && !hasClose) : !hasClose;

                Response resp;
                try {
                    resp = handler.handle(req);
                } catch (Throwable t) {
                    resp = null;
                }
                int status = 500;
                Map<String, String> headers = Map.of();
                byte[] body = new byte[0];
                if (resp != null && resp.status() >= 100 && resp.status() <= 599) {
                    status = resp.status();
                    if (resp.headers() != null) {
                        headers = resp.headers();
                    }
                    if (resp.body() != null) {
                        body = resp.body();
                    }
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream(body.length + 256);
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    if (k == null || e.getValue() == null) {
                        continue;
                    }
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("content-length") || lk.equals("connection")) {
                        continue;
                    }
                    sb.append(k).append(": ").append(e.getValue()).append("\r\n");
                }
                sb.append("Content-Length: ").append(body.length).append("\r\n");
                if (!keepAlive) {
                    sb.append("Connection: close\r\n");
                } else if (http10) {
                    sb.append("Connection: keep-alive\r\n");
                }
                sb.append("\r\n");
                bo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                bo.write(body);
                out.write(bo.toByteArray());
                out.flush();
                if (!keepAlive) {
                    try {
                        socket.shutdownOutput();
                    } catch (IOException e) {
                        // ignore
                    }
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            // connection broken or closed
        } finally {
            connections.remove(socket);
            closeQuietly(socket);
        }
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String p : value.split(",")) {
            if (p.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }

    /** Reads a line; null at clean EOF when allowed (no bytes read). */
    private static byte[] readLine(InputStream in, boolean eofOk) throws IOException, Malformed {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (n == 0 && eofOk) {
                    return null;
                }
                throw new Malformed("unexpected end of input");
            }
            if (b == '\n') {
                byte[] a = buf.toByteArray();
                if (a.length > 0 && a[a.length - 1] == '\r') {
                    return java.util.Arrays.copyOf(a, a.length - 1);
                }
                return a;
            }
            buf.write(b);
            n++;
            if (n > MAX_LINE) {
                throw new Malformed("line too long");
            }
        }
    }

    private static Object[] parseRequest(byte[] firstLine, InputStream in) throws IOException, Malformed {
        String line = new String(firstLine, StandardCharsets.ISO_8859_1);
        String[] parts = line.split(" ", -1);
        if (parts.length != 3) {
            throw new Malformed("bad request line");
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (method.isEmpty()) {
            throw new Malformed("bad method");
        }
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new Malformed("bad method");
            }
        }
        if (target.isEmpty() || target.charAt(0) != '/') {
            throw new Malformed("bad target");
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new Malformed("bad version");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            byte[] hl = readLine(in, false);
            if (hl.length == 0) {
                break;
            }
            String h = new String(hl, StandardCharsets.UTF_8);
            int colon = h.indexOf(':');
            if (colon <= 0) {
                throw new Malformed("bad header");
            }
            String name = h.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = trimSpTab(h.substring(colon + 1));
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        String path;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        path = decode(rawPath, false);
        if (q >= 0) {
            for (String piece : target.substring(q + 1).split("&")) {
                if (piece.isEmpty()) {
                    continue;
                }
                int eq = piece.indexOf('=');
                String k = eq < 0 ? piece : piece.substring(0, eq);
                String v = eq < 0 ? "" : piece.substring(eq + 1);
                query.put(decode(k, true), decode(v, true));
            }
        }

        byte[] body;
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(in);
        } else {
            String cl = headers.get("content-length");
            if (cl == null) {
                body = new byte[0];
            } else {
                long len = parseLength(cl);
                if (len > Integer.MAX_VALUE - 8) {
                    throw new Malformed("body too large");
                }
                body = readFully(in, (int) len);
            }
        }
        Request req = new Request(method, path, Collections.unmodifiableMap(query),
                Collections.unmodifiableMap(headers), body);
        return new Object[] {req, version};
    }

    private static String trimSpTab(String s) {
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

    private static long parseLength(String s) throws Malformed {
        if (s.isEmpty() || s.length() > 18) {
            if (!s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return Long.MAX_VALUE; // huge but valid integer
            }
            throw new Malformed("bad content-length");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new Malformed("bad content-length");
            }
        }
        return Long.parseLong(s);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException, Malformed {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) {
                throw new Malformed("unexpected end of body");
            }
            off += r;
        }
        return b;
    }

    private static byte[] readChunked(InputStream in) throws IOException, Malformed {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sl = new String(readLine(in, false), StandardCharsets.ISO_8859_1);
            int semi = sl.indexOf(';');
            String hex = trimSpTab(semi < 0 ? sl : sl.substring(0, semi));
            if (hex.isEmpty() || hex.length() > 15) {
                throw new Malformed("bad chunk size");
            }
            long size = 0;
            for (int i = 0; i < hex.length(); i++) {
                int d = Character.digit(hex.charAt(i), 16);
                if (d < 0 || hex.charAt(i) > 127) {
                    throw new Malformed("bad chunk size");
                }
                size = size * 16 + d;
            }
            if (size == 0) {
                while (true) {
                    byte[] tl = readLine(in, false);
                    if (tl.length == 0) {
                        break;
                    }
                }
                return body.toByteArray();
            }
            if (size > Integer.MAX_VALUE - 8) {
                throw new Malformed("chunk too large");
            }
            body.write(readFully(in, (int) size));
            byte[] end = readLine(in, false);
            if (end.length != 0) {
                throw new Malformed("bad chunk ending");
            }
        }
    }

    private static String decode(String s, boolean plusIsSpace) throws Malformed {
        boolean simple = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || c > 127 || (plusIsSpace && c == '+')) {
                simple = false;
                break;
            }
        }
        if (simple) {
            return s;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new Malformed("bad escape");
                }
                int h = Character.digit(s.charAt(i + 1), 16);
                int l = Character.digit(s.charAt(i + 2), 16);
                if (h < 0 || l < 0 || s.charAt(i + 1) > 127 || s.charAt(i + 2) > 127) {
                    throw new Malformed("bad escape");
                }
                bo.write(h * 16 + l);
                i += 2;
            } else if (plusIsSpace && c == '+') {
                bo.write(' ');
            } else {
                bo.write(c & 0xFF);
            }
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }
}
