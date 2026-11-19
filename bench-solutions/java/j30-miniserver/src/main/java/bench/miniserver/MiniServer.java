package bench.miniserver;

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

/** Minimal HTTP/1.1 server on raw sockets: keep-alive, pipelining, chunked bodies. */
public final class MiniServer implements AutoCloseable {
    private static final int MAX_LINE = 1 << 20;
    private static final int MAX_HEADER_BYTES = 8 << 20;

    private final int requestedPort;
    private final Handler handler;
    private final Object lock = new Object();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private volatile int actualPort = -1;
    private boolean started;
    private volatile boolean closed;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    /** Starts listening on the loopback address. */
    public void start() throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("server is closed");
            }
            if (started) {
                throw new IllegalStateException("server already started");
            }
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 256);
            } catch (IOException | RuntimeException e) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                    // best effort
                }
                throw e;
            }
            serverSocket = ss;
            actualPort = ss.getLocalPort();
            started = true;
            Thread t = new Thread(() -> acceptLoop(ss), "miniserver-accept-" + actualPort);
            t.setDaemon(true);
            t.start();
        }
    }

    public int port() {
        if (actualPort < 0) {
            throw new IllegalStateException("server not started");
        }
        return actualPort;
    }

    @Override
    public void close() {
        ServerSocket ss;
        synchronized (lock) {
            closed = true;
            ss = serverSocket;
        }
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        for (Socket s : connections) {
            closeQuietly(s);
        }
        connections.clear();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best effort
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
            connections.add(s);
            if (closed) {
                closeQuietly(s);
                connections.remove(s);
                return;
            }
            Thread t = new Thread(() -> serve(s), "miniserver-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ------------------------------------------------------------------ API

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }

    // ----------------------------------------------------------- connection

    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    private static final class Parsed {
        Request request;
        boolean http11;
        boolean close;
        boolean keepAliveToken;
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Reader reader = new Reader(in);
            while (!closed) {
                Parsed p;
                try {
                    p = readRequest(reader);
                } catch (Malformed m) {
                    out.write(errorResponse());
                    out.flush();
                    lingeringClose(socket, in);
                    return;
                }
                if (p == null) {
                    return;
                }
                Response resp = invoke(p.request);
                boolean close = p.close || (!p.http11 && !p.keepAliveToken);
                out.write(render(resp, close, !p.http11 && !close));
                out.flush();
                if (close) {
                    lingeringClose(socket, in);
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            // connection dropped or server closing
        } finally {
            closeQuietly(socket);
            connections.remove(socket);
        }
    }

    /** Half-closes, drains unread input briefly so the peer sees the response, then closes. */
    private static void lingeringClose(Socket socket, InputStream in) {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(250);
            byte[] sink = new byte[4096];
            long total = 0;
            long deadline = System.nanoTime() + 500_000_000L;
            while (total < (1 << 20) && System.nanoTime() < deadline) {
                int n = in.read(sink);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (SocketTimeoutException e) {
            // peer idle: close now
        } catch (IOException | RuntimeException e) {
            // already gone
        }
    }

    private Response invoke(Request req) {
        Response r;
        try {
            r = handler.handle(req);
        } catch (Throwable t) {
            return null;
        }
        if (r == null || r.status() < 100 || r.status() > 599) {
            return null;
        }
        return r;
    }

    private static byte[] errorResponse() {
        String s = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] render(Response r, boolean close, boolean keepAlive10) {
        int status = 500;
        Map<String, String> headers = null;
        byte[] body = null;
        if (r != null) {
            status = r.status();
            headers = r.headers();
            body = r.body();
        }
        if (body == null) {
            body = new byte[0];
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) {
                    continue;
                }
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("content-length") || lk.equals("connection")) {
                    continue;
                }
                sb.append(clean(k)).append(": ").append(clean(v)).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (close) {
            sb.append("Connection: close\r\n");
        } else if (keepAlive10) {
            sb.append("Connection: keep-alive\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private static String clean(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
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

    // -------------------------------------------------------------- parsing

    private static final class Reader {
        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private int pos;
        private int lim;

        Reader(InputStream in) {
            this.in = in;
        }

        /** Returns the next byte or -1 at end of input. */
        int read() throws IOException {
            if (pos >= lim) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) {
                    return -1;
                }
                pos = 0;
                lim = n;
            }
            return buf[pos++] & 0xFF;
        }

        /** Reads up to len bytes into out; returns the number read or -1 at end of input. */
        int read(byte[] out, int len) throws IOException {
            if (pos >= lim) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) {
                    return -1;
                }
                pos = 0;
                lim = n;
            }
            int c = Math.min(len, lim - pos);
            System.arraycopy(buf, pos, out, 0, c);
            pos += c;
            return c;
        }
    }

    /**
     * Reads one line (terminated by LF, optional CR before it removed). Returns null on end of
     * input before any byte; throws Malformed on end of input in the middle of a line.
     */
    private static String readLine(Reader r, boolean eofOkAtStart) throws IOException, Malformed {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
        int c = r.read();
        if (c < 0) {
            if (eofOkAtStart) {
                return null;
            }
            throw new Malformed("unexpected end of input");
        }
        while (true) {
            if (c < 0) {
                throw new Malformed("unexpected end of input");
            }
            if (c == '\n') {
                break;
            }
            bo.write(c);
            if (bo.size() > MAX_LINE) {
                throw new Malformed("line too long");
            }
            c = r.read();
        }
        byte[] b = bo.toByteArray();
        int len = b.length;
        if (len > 0 && b[len - 1] == '\r') {
            len--;
        }
        return new String(b, 0, len, StandardCharsets.UTF_8);
    }

    private static Parsed readRequest(Reader r) throws IOException, Malformed {
        String line = readLine(r, true);
        if (line == null) {
            return null;
        }
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
            char ch = method.charAt(i);
            if (ch < 'A' || ch > 'Z') {
                throw new Malformed("bad method");
            }
        }
        if (target.isEmpty() || target.charAt(0) != '/') {
            throw new Malformed("bad target");
        }
        boolean http11;
        if (version.equals("HTTP/1.1")) {
            http11 = true;
        } else if (version.equals("HTTP/1.0")) {
            http11 = false;
        } else {
            throw new Malformed("bad version");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        int total = 0;
        while (true) {
            String h = readLine(r, false);
            if (h.isEmpty()) {
                break;
            }
            total += h.length();
            if (total > MAX_HEADER_BYTES) {
                throw new Malformed("headers too large");
            }
            int colon = h.indexOf(':');
            if (colon <= 0) {
                throw new Malformed("bad header line");
            }
            String name = h.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = trimSpTab(h.substring(colon + 1));
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        String path;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        path = percentDecode(rawPath, false);
        if (q >= 0) {
            String rawQuery = target.substring(q + 1);
            for (String piece : rawQuery.split("&", -1)) {
                if (piece.isEmpty()) {
                    continue;
                }
                int eq = piece.indexOf('=');
                String k = eq < 0 ? piece : piece.substring(0, eq);
                String v = eq < 0 ? "" : piece.substring(eq + 1);
                query.put(percentDecode(k, true), percentDecode(v, true));
            }
        }

        byte[] body;
        String te = headers.get("transfer-encoding");
        if (te != null && isChunked(te)) {
            body = readChunked(r);
        } else {
            String cl = headers.get("content-length");
            if (cl != null) {
                body = readFixed(r, parseLength(cl));
            } else {
                body = new byte[0];
            }
        }

        Parsed p = new Parsed();
        p.http11 = http11;
        String conn = headers.get("connection");
        if (conn != null) {
            for (String tok : conn.split(",")) {
                String t = tok.trim();
                if (t.equalsIgnoreCase("close")) {
                    p.close = true;
                } else if (t.equalsIgnoreCase("keep-alive")) {
                    p.keepAliveToken = true;
                }
            }
        }
        p.request = new Request(method, path, Collections.unmodifiableMap(query),
            Collections.unmodifiableMap(headers), body);
        return p;
    }

    private static boolean isChunked(String te) {
        String[] toks = te.split(",");
        return toks[toks.length - 1].trim().equalsIgnoreCase("chunked");
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

    private static long parseLength(String v) throws Malformed {
        if (v.isEmpty()) {
            throw new Malformed("bad content-length");
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                throw new Malformed("bad content-length");
            }
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static byte[] readFixed(Reader r, long len) throws IOException, Malformed {
        if (len == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(len, 1 << 16));
        copy(r, len, bo);
        return bo.toByteArray();
    }

    private static void copy(Reader r, long len, ByteArrayOutputStream bo) throws IOException, Malformed {
        byte[] tmp = new byte[8192];
        long left = len;
        while (left > 0) {
            int n = r.read(tmp, (int) Math.min(left, tmp.length));
            if (n < 0) {
                throw new Malformed("unexpected end of input in body");
            }
            bo.write(tmp, 0, n);
            left -= n;
        }
    }

    private static byte[] readChunked(Reader r) throws IOException, Malformed {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(r, false);
            int semi = line.indexOf(';');
            String size = trimSpTab(semi < 0 ? line : line.substring(0, semi));
            if (size.isEmpty() || size.length() > 15) {
                throw new Malformed("bad chunk size");
            }
            long n = 0;
            for (int i = 0; i < size.length(); i++) {
                int d = Character.digit(size.charAt(i), 16);
                if (d < 0 || size.charAt(i) > 'f') {
                    throw new Malformed("bad chunk size");
                }
                n = n * 16 + d;
            }
            if (n == 0) {
                while (!readLine(r, false).isEmpty()) {
                    // ignore trailers
                }
                return bo.toByteArray();
            }
            copy(r, n, bo);
            int c = r.read();
            if (c == '\r') {
                c = r.read();
            }
            if (c != '\n') {
                throw new Malformed("bad chunk ending");
            }
        }
    }

    private static String percentDecode(String s, boolean plusIsSpace) throws Malformed {
        if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) {
            return s;
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '%') {
                if (i + 2 >= in.length) {
                    throw new Malformed("bad escape");
                }
                int h = Character.digit((char) (in[i + 1] & 0xFF), 16);
                int l = Character.digit((char) (in[i + 2] & 0xFF), 16);
                if (h < 0 || l < 0 || (in[i + 1] & 0x80) != 0 || (in[i + 2] & 0x80) != 0) {
                    throw new Malformed("bad escape");
                }
                out.write(h * 16 + l);
                i += 2;
            } else if (b == '+' && plusIsSpace) {
                out.write(' ');
            } else {
                out.write(b);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
