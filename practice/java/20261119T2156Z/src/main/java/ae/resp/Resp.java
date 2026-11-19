package ae.resp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** RESP2 codec. Parsing reads byte by byte, so wrap sockets/files in a BufferedInputStream. */
public final class Resp {
    static final int MAX_DEPTH = 32;
    static final int MAX_BULK = 1 << 20;
    static final int MAX_ARRAY = 1024;
    static final int MAX_LINE = 64 * 1024;

    private Resp() { }

    /** A RESP value. */
    public sealed interface Value permits Simple, Err, Int, Bulk, Arr { }

    public record Simple(String text) implements Value { }

    public record Err(String message) implements Value { }

    public record Int(long value) implements Value { }

    /** A bulk string; {@code text == null} is the nil bulk. */
    public record Bulk(String text) implements Value { }

    /** An array; {@code items == null} is the nil array. */
    public record Arr(List<Value> items) implements Value { }

    /** Builds a command: an array of bulk strings. */
    public static Value command(String... parts) {
        return command(List.of(parts));
    }

    public static Value command(List<String> parts) {
        List<Value> items = new ArrayList<>(parts.size());
        for (String p : parts) {
            items.add(new Bulk(p));
        }
        return new Arr(items);
    }

    public static byte[] encode(Value v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encode(v, out);
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void line(ByteArrayOutputStream out, char prefix, String body) {
        if (body.indexOf('\r') >= 0 || body.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("line value must not contain CR or LF");
        }
        out.write(prefix);
        put(out, body);
        put(out, "\r\n");
    }

    private static void encode(Value v, ByteArrayOutputStream out) {
        switch (v) {
            case Simple s -> line(out, '+', s.text());
            case Err e -> line(out, '-', e.message());
            case Int i -> line(out, ':', Long.toString(i.value()));
            case Bulk b -> {
                if (b.text() == null) {
                    put(out, "$-1\r\n");
                } else {
                    byte[] bytes = b.text().getBytes(StandardCharsets.UTF_8);
                    put(out, "$" + bytes.length + "\r\n");
                    out.writeBytes(bytes);
                    put(out, "\r\n");
                }
            }
            case Arr a -> {
                if (a.items() == null) {
                    put(out, "*-1\r\n");
                } else {
                    put(out, "*" + a.items().size() + "\r\n");
                    for (Value x : a.items()) {
                        encode(x, out);
                    }
                }
            }
        }
    }

    /** Reads one value; returns null on clean EOF before the first byte. */
    public static Value parse(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        return parseBody(in, first, 1);
    }

    private static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new RespException("unexpected end of input", true);
        }
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = next(in);
            if (b == '\r') {
                int n = next(in);
                if (n != '\n') {
                    throw new RespException("CR not followed by LF", false);
                }
                return buf.toString(StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                throw new RespException("bare LF", false);
            }
            buf.write(b);
            if (buf.size() > MAX_LINE) {
                throw new RespException("line too long", false);
            }
        }
    }

    private static long number(String s) throws RespException {
        if (!s.matches("-?[0-9]{1,18}")) {
            throw new RespException("bad integer '" + s + "'", false);
        }
        return Long.parseLong(s);
    }

    private static Value parseBody(InputStream in, int prefix, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new RespException("nesting too deep", false);
        }
        switch (prefix) {
            case '+':
                return new Simple(readLine(in));
            case '-':
                return new Err(readLine(in));
            case ':':
                return new Int(number(readLine(in)));
            case '$': {
                long len = number(readLine(in));
                if (len == -1) {
                    return new Bulk(null);
                }
                if (len < 0 || len > MAX_BULK) {
                    throw new RespException("bad bulk length " + len, false);
                }
                byte[] data = new byte[(int) len];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) next(in);
                }
                if (next(in) != '\r') {
                    throw new RespException("bulk not terminated by CRLF", false);
                }
                if (next(in) != '\n') {
                    throw new RespException("bulk not terminated by CRLF", false);
                }
                return new Bulk(new String(data, StandardCharsets.UTF_8));
            }
            case '*': {
                long n = number(readLine(in));
                if (n == -1) {
                    return new Arr(null);
                }
                if (n < 0 || n > MAX_ARRAY) {
                    throw new RespException("bad array length " + n, false);
                }
                List<Value> items = new ArrayList<>((int) n);
                for (long i = 0; i < n; i++) {
                    items.add(parseBody(in, next(in), depth + 1));
                }
                return new Arr(items);
            }
            default:
                throw new RespException("unknown type prefix " + prefix, false);
        }
    }
}
