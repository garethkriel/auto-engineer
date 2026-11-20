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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal HTTP/1.1 server on raw sockets. */
public final class MiniServer implements AutoCloseable {
    private static final int MAX_LINE = 1 << 20;

    private final int requestedPort;
    private final Handler handler;
    private final Set<Socket> connections = new HashSet<>();
    private ServerSocket server;
    private boolean closed;

    public MiniServer(int port, Handler handler) {
        if (port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("bad port or handler");
        }
        this.requestedPort = port;
        this.handler = handler;
    }

    /** Handles requests. */
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** A parsed request. */
    public record Request(String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) { }

    /** A response to send. */
    public record Response(int status, Map<String, String> headers, byte[] body) { }

    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        if (server != null) {
            return;
        }
        ServerSocket ss = new ServerSocket(requestedPort, 256, InetAddress.getLoopbackAddress());
        server = ss;
        Thread t = new Thread(() -> acceptLoop(ss), "miniserver-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("not started");
        }
        return server.getLocalPort();
    }

    @Override
    public void close() {
        Set<Socket> toClose;
        ServerSocket ss;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ss = server;
            toClose = new HashSet<>(connections);
            connections.clear();
        }
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        for (Socket s : toClose) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
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
            boolean ok;
            synchronized (this) {
                ok = !closed;
                if (ok) {
                    connections.add(s);
                }
            }
            if (!ok) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
                return;
            }
            Thread.ofVirtual().start(() -> serve(s));
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = s.getOutputStream();
            while (true) {
                Request req;
                boolean http10;
                try {
                    String line = readLine(in);
                    if (line == null) {
                        return;
                    }
                    req = null;
                    http10 = false;
                    String[] parts = line.split(" ", -1);
                    if (parts.length != 3 || !parts[0].matches("[A-Z]+") || !parts[1].startsWith("/")
                            || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
                        throw new Malformed("request line");
                    }
                    http10 = parts[2].equals("HTTP/1.0");
                    Map<String, String> headers = readHeaders(in);
                    byte[] body = readBody(in, headers);
                    String target = parts[1];
                    int q = target.indexOf('?');
                    String path = decode(q < 0 ? target : target.substring(0, q), false);
                    Map<String, String> query = q < 0 ? new LinkedHashMap<>() : parseQuery(target.substring(q + 1));
                    req = new Request(parts[0], path, Collections.unmodifiableMap(query),
                            Collections.unmodifiableMap(headers), body);
                } catch (Malformed m) {
                    byte[] r = ("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1);
                    try {
                        out.write(r);
                        out.flush();
                        s.shutdownOutput();
                        s.setSoTimeout(300);
                        byte[] buf = new byte[4096];
                        long deadline = System.nanoTime() + 300_000_000L;
                        while (System.nanoTime() < deadline && in.read(buf) >= 0) {
                            // drain so close does not reset the connection
                        }
                    } catch (IOException ignored) {
                        // best effort
                    }
                    return;
                }
                String conn = req.headers().get("connection");
                boolean closeIt;
                boolean keepAlive = false;
                if (http10) {
                    keepAlive = hasToken(conn, "keep-alive");
                    closeIt = !keepAlive;
                } else {
                    closeIt = hasToken(conn, "close");
                }
                Response resp = null;
                try {
                    resp = handler.handle(req);
                } catch (Throwable t) {
                    resp = null;
                }
                writeResponse(out, resp, closeIt, keepAlive);
                if (closeIt) {
                    return;
                }
            }
        } catch (IOException e) {
            // connection broken or closed
        } finally {
            synchronized (this) {
                connections.remove(s);
            }
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
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

    private static void writeResponse(OutputStream out, Response resp, boolean closeIt, boolean keepAlive)
            throws IOException {
        int status = 500;
        Map<String, String> hdrs = null;
        byte[] body = new byte[0];
        if (resp != null && resp.status() >= 100 && resp.status() <= 599) {
            status = resp.status();
            hdrs = resp.headers();
            if (resp.body() != null) {
                body = resp.body();
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (hdrs != null) {
            for (Map.Entry<String, String> e : hdrs.entrySet()) {
                String k = e.getKey();
                if (k == null || k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("connection")) {
                    continue;
                }
                sb.append(k).append(": ").append(e.getValue() == null ? "" : e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (closeIt) {
            sb.append("Connection: close\r\n");
        } else if (keepAlive) {
            sb.append("Connection: keep-alive\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        out.write(all);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }

    /** Reads a line terminated by LF (CR stripped); null on EOF before any byte. */
    private static String readLine(InputStream in) throws IOException, Malformed {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] b = bo.toByteArray();
                int n = b.length;
                if (n > 0 && b[n - 1] == '\r') {
                    n--;
                }
                return new String(b, 0, n, StandardCharsets.ISO_8859_1);
            }
            bo.write(c);
            if (bo.size() > MAX_LINE) {
                throw new Malformed("line too long");
            }
        }
        if (bo.size() == 0) {
            return null;
        }
        throw new Malformed("eof mid-request");
    }

    private static String needLine(InputStream in) throws IOException, Malformed {
        String l = readLine(in);
        if (l == null) {
            throw new Malformed("eof mid-request");
        }
        return l;
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException, Malformed {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String l = needLine(in);
            if (l.isEmpty()) {
                return headers;
            }
            int i = l.indexOf(':');
            if (i < 0) {
                throw new Malformed("header without colon");
            }
            String name = l.substring(0, i).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new Malformed("header without name");
            }
            String value = trimOws(l.substring(i + 1));
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
    }

    private static String trimOws(String v) {
        int a = 0;
        int b = v.length();
        while (a < b && (v.charAt(a) == ' ' || v.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (v.charAt(b - 1) == ' ' || v.charAt(b - 1) == '\t')) {
            b--;
        }
        return v.substring(a, b);
    }

    private static byte[] readBody(InputStream in, Map<String, String> h) throws IOException, Malformed {
        String cl = h.get("content-length");
        long len = -1;
        if (cl != null) {
            if (!cl.matches("[0-9]{1,18}")) {
                throw new Malformed("bad content-length");
            }
            len = Long.parseLong(cl);
        }
        String te = h.get("transfer-encoding");
        if (te != null && hasToken(te, "chunked")) {
            return readChunked(in);
        }
        if (len <= 0) {
            return new byte[0];
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(len, 1 << 16));
        byte[] buf = new byte[8192];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new Malformed("eof in body");
            }
            bo.write(buf, 0, n);
            left -= n;
        }
        return bo.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException, Malformed {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        while (true) {
            String l = needLine(in);
            int semi = l.indexOf(';');
            String sz = trimOws(semi < 0 ? l : l.substring(0, semi));
            if (!sz.matches("[0-9a-fA-F]{1,15}")) {
                throw new Malformed("bad chunk size");
            }
            long size = Long.parseLong(sz, 16);
            if (size == 0) {
                while (!needLine(in).isEmpty()) {
                    // trailers ignored
                }
                return bo.toByteArray();
            }
            byte[] buf = new byte[8192];
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new Malformed("eof in chunk");
                }
                bo.write(buf, 0, n);
                left -= n;
            }
            int c = in.read();
            if (c == '\r') {
                c = in.read();
            }
            if (c != '\n') {
                throw new Malformed("bad chunk ending");
            }
        }
    }

    private static Map<String, String> parseQuery(String q) throws Malformed {
        Map<String, String> m = new LinkedHashMap<>();
        for (String piece : q.split("&")) {
            if (piece.isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            String k = eq < 0 ? piece : piece.substring(0, eq);
            String v = eq < 0 ? "" : piece.substring(eq + 1);
            m.put(decode(k, true), decode(v, true));
        }
        return m;
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    private static String decode(String s, boolean plusIsSpace) throws Malformed {
        if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) {
            return decodeLatin(s);
        }
        // Target was read as ISO-8859-1; convert to raw bytes first.
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            int c = raw[i] & 0xFF;
            if (c == '%') {
                if (i + 2 >= raw.length) {
                    throw new Malformed("bad escape");
                }
                int hi = hex((char) (raw[i + 1] & 0xFF));
                int lo = hex((char) (raw[i + 2] & 0xFF));
                if (hi < 0 || lo < 0) {
                    throw new Malformed("bad escape");
                }
                bo.write(hi * 16 + lo);
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                bo.write(' ');
            } else {
                bo.write(c);
            }
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String decodeLatin(String s) {
        return new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
}
