package ae.tar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** POSIX pax extended-header records: {@code "<len> key=value\n"} where len counts the whole record. */
final class Pax {
    /** At most this many digits in a record length (so the length always fits an int). */
    private static final int MAX_DIGITS = 7;

    private Pax() { }

    /** Formats one record; the length includes its own digits. */
    static String record(String key, String value) {
        int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
        int digits = String.valueOf(body).length();
        int total = body + digits;
        if (String.valueOf(total).length() != digits) { // e.g. body 98 -> 98 + 2 = 100 needs 3 digits
            digits++;
            total = body + digits;
        }
        return total + " " + key + "=" + value + "\n";
    }

    /** Parses all records strictly; a later duplicate key replaces the earlier value. */
    static Map<String, String> parse(byte[] data) throws TarFormatException {
        Map<String, String> out = new LinkedHashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int d = pos;
            long len = 0;
            while (d < data.length && data[d] >= '0' && data[d] <= '9') {
                len = len * 10 + (data[d] - '0');
                d++;
                if (d - pos > MAX_DIGITS) {
                    throw new TarFormatException("pax record length has too many digits");
                }
            }
            int digits = d - pos;
            if (digits == 0 || data[pos] == '0' || d >= data.length || data[d] != ' ') {
                throw new TarFormatException("pax record at " + pos + " has a malformed length");
            }
            if (len > data.length - pos || len < digits + 4) { // shortest record: "<len> k=\n"
                throw new TarFormatException("pax record length " + len + " at " + pos + " is out of range");
            }
            int end = pos + (int) len;
            if (data[end - 1] != '\n') {
                throw new TarFormatException("pax record at " + pos + " does not end with a newline");
            }
            int bodyStart = d + 1;
            int eq = bodyStart;
            while (eq < end - 1 && data[eq] != '=') {
                eq++;
            }
            if (eq >= end - 1) {
                throw new TarFormatException("pax record at " + pos + " is missing '='");
            }
            if (eq == bodyStart) {
                throw new TarFormatException("pax record at " + pos + " has an empty key");
            }
            String key = Text.decode(data, bodyStart, eq - bodyStart);
            String value = Text.decode(data, eq + 1, end - 1 - (eq + 1));
            if (key == null || value == null) {
                throw new TarFormatException("pax record at " + pos + " is not valid UTF-8");
            }
            out.put(key, value);
            pos = end;
        }
        return out;
    }

    /** Concatenated records in the given order. */
    static byte[] encode(Map<String, String> records) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : records.entrySet()) {
            o.writeBytes(record(e.getKey(), e.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        return o.toByteArray();
    }
}
