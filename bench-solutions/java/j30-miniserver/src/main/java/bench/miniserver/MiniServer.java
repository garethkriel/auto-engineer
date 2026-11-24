package bench.miniserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
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

/** A small HTTP/1.1 server on raw sockets. */
public final class MiniServer implements AutoCloseable {
    private static final int MAX_LINE = 1 << 20;

    private final int requestedPort;
    private final Handler handler;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private boolean started;
    private boolean closed;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("invalid port or handler");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        if (started) {
            throw new IllegalStateException("already started");
        }
        serverSocket = new ServerSocket(requestedPort, 128, InetAddress.getLoopbackAddress());
        started = true;
        ServerSocket ss = serverSocket;
        acceptThread = new Thread(() -> acceptLoop(ss), "miniserver-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized int port() {
        if (!started) {
            throw new IllegalStateException("not started");
        }
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        ServerSocket ss;
        Thread at;
        synchronized (this) {
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
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        if (at != null) {
            try {
                at.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // sockets accepted while closing
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private void acceptLoop(ServerSocket ss) {
        while (!isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (isClosed() || ss.isClosed()) {
                    return;
                }
                continue;
            }
            open.add(s);
            if (isClosed()) {
                closeQuietly(s);
                open.remove(s);
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
            // closing
        }
    }

    // ------------------------------------------------------------------ connection

    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    private static final class In {
        private final InputStream in;
        private final byte[] buf = new byte[16384];
        private int pos;
        private int lim;

        In(InputStream in) {
            this.in = in;
        }

        /** Returns next byte or -1 at EOF. */
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

        /** Reads a line (without terminator); null on EOF before any byte; Malformed on EOF mid-line. */
        String readLine() throws IOException, Malformed {
            ByteArrayOutputStream b = new ByteArrayOutputStream(64);
            boolean any = false;
            while (true) {
                int c = read();
                if (c < 0) {
                    if (!any) {
                        return null;
                    }
                    throw new Malformed("unexpected end of input");
                }
                any = true;
                if (c == '\n') {
                    byte[] arr = b.toByteArray();
                    int len = arr.length;
                    if (len > 0 && arr[len - 1] == '\r') {
                        len--;
                    }
                    return new String(arr, 0, len, StandardCharsets.UTF_8);
                }
                b.write(c);
                if (b.size() > MAX_LINE) {
                    throw new Malformed("line too long");
                }
            }
        }

        byte[] readBytes(long n) throws IOException, Malformed {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(n, 1 << 16));
            long left = n;
            while (left > 0) {
                if (pos >= lim) {
                    int r = in.read(buf, 0, buf.length);
                    if (r <= 0) {
                        throw new Malformed("unexpected end of input");
                    }
                    pos = 0;
                    lim = r;
                }
                int take = (int) Math.min(left, lim - pos);
                out.write(buf, pos, take);
                pos += take;
                left -= take;
            }
            return out.toByteArray();
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            In in = new In(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (!isClosed()) {
                Parsed req;
                try {
                    req = readRequest(in);
                } catch (Malformed m) {
                    writeRaw(out, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
                    lingerClose(s);
                    return;
                }
                if (req == null) {
                    return;
                }
                Response resp = invoke(req.request);
                boolean keep = req.keepAlive;
                byte[] head = buildResponse(resp, keep, req.http10);
                out.write(head);
                out.flush();
                if (!keep) {
                    lingerClose(s);
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            // connection dropped or server closing
        } finally {
            open.remove(s);
            closeQuietly(s);
        }
    }

    private static void writeRaw(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Half-closes, drains briefly so the peer sees the response before any reset, then closes. */
    private static void lingerClose(Socket s) {
        try {
            s.shutdownOutput();
            s.setSoTimeout(200);
            InputStream in = s.getInputStream();
            byte[] junk = new byte[4096];
            long deadline = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < deadline) {
                if (in.read(junk) < 0) {
                    break;
                }
            }
        } catch (SocketTimeoutException ignored) {
            // done waiting
        } catch (IOException ignored) {
            // peer gone
        }
    }

    private Response invoke(Request r) {
        try {
            Response resp = handler.handle(r);
            if (resp == null || resp.status() < 100 || resp.status() > 599) {
                return new Response(500, null, null);
            }
            return resp;
        } catch (Throwable t) {
            return new Response(500, null, null);
        }
    }

    private static String reason(int status) {
        return switch (status) {
            case 100 -> "Continue";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 418 -> "I'm a teapot";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static String clean(String v) {
        return v.replace('\r', ' ').replace('\n', ' ');
    }

    private static byte[] buildResponse(Response resp, boolean keep, boolean http10) {
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        StringBuilder sb = new StringBuilder(128);
        sb.append("HTTP/1.1 ").append(resp.status()).append(' ').append(reason(resp.status())).append("\r\n");
        if (resp.headers() != null) {
            for (Map.Entry<String, String> e : resp.headers().entrySet()) {
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
        if (!keep) {
            sb.append("Connection: close\r\n");
        } else if (http10) {
            sb.append("Connection: keep-alive\r\n");
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    // ------------------------------------------------------------------ request parsing

    private static final class Parsed {
        Request request;
        boolean keepAlive;
        boolean http10;
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static Parsed readRequest(In in) throws IOException, Malformed {
        String line = in.readLine();
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
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new Malformed("bad method");
            }
        }
        if (target.isEmpty() || target.charAt(0) != '/') {
            throw new Malformed("bad target");
        }
        boolean http10;
        if (version.equals("HTTP/1.1")) {
            http10 = false;
        } else if (version.equals("HTTP/1.0")) {
            http10 = true;
        } else {
            throw new Malformed("bad version");
        }

        String path;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        path = decode(rawPath, false);
        if (q >= 0) {
            String rawQuery = target.substring(q + 1);
            for (String piece : rawQuery.split("&")) {
                if (piece.isEmpty()) {
                    continue;
                }
                int eq = piece.indexOf('=');
                String name = eq < 0 ? piece : piece.substring(0, eq);
                String value = eq < 0 ? "" : piece.substring(eq + 1);
                query.put(decode(name, true), decode(value, true));
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String h = in.readLine();
            if (h == null) {
                throw new Malformed("unexpected end of input");
            }
            if (h.isEmpty()) {
                break;
            }
            int colon = h.indexOf(':');
            if (colon < 0) {
                throw new Malformed("bad header line");
            }
            String name = trimOws(h.substring(0, colon));
            if (name.isEmpty()) {
                throw new Malformed("empty header name");
            }
            String value = trimOws(h.substring(colon + 1));
            name = name.toLowerCase(Locale.ROOT);
            String prev = headers.get(name);
            headers.put(name, prev == null ? value : prev + ", " + value);
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
                long n = parseLength(cl);
                body = n == 0 ? new byte[0] : in.readBytes(n);
            }
        }

        Parsed p = new Parsed();
        p.http10 = http10;
        String conn = headers.get("connection");
        if (http10) {
            p.keepAlive = hasToken(conn, "keep-alive") && !hasToken(conn, "close");
        } else {
            p.keepAlive = !hasToken(conn, "close");
        }
        p.request = new Request(method, path, Collections.unmodifiableMap(query),
            Collections.unmodifiableMap(headers), body);
        return p;
    }

    private static String trimOws(String s) {
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
            throw new Malformed("bad content-length");
        }
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new Malformed("bad content-length");
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static byte[] readChunked(In in) throws IOException, Malformed {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new Malformed("unexpected end of input");
            }
            int semi = line.indexOf(';');
            String size = trimOws(semi < 0 ? line : line.substring(0, semi));
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
                while (true) {
                    String t = in.readLine();
                    if (t == null) {
                        throw new Malformed("unexpected end of input");
                    }
                    if (t.isEmpty()) {
                        return body.toByteArray();
                    }
                }
            }
            body.writeBytes(in.readBytes(n));
            int c = in.read();
            if (c == '\r') {
                c = in.read();
            }
            if (c != '\n') {
                throw new Malformed("bad chunk ending");
            }
        }
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
        if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new Malformed("bad percent escape");
                }
                int h1 = hex(s.charAt(i + 1));
                int h2 = hex(s.charAt(i + 2));
                if (h1 < 0 || h2 < 0) {
                    throw new Malformed("bad percent escape");
                }
                out.write(h1 * 16 + h2);
                i += 3;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
                i++;
            } else {
                int cp = s.codePointAt(i);
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ public types

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    public record Response(int status, Map<String, String> headers, byte[] body) { }
}
