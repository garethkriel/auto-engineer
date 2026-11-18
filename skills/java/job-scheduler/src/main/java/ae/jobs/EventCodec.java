package ae.jobs;

import ae.jobs.journal.CorruptJournalException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Line format: {@code crc32hex SPACE body}; body = tab-separated percent-encoded fields. */
final class EventCodec {
    private EventCodec() {
    }

    static String encode(Event e) {
        String body = switch (e) {
            case Event.Submitted s -> join("S", enc(s.id()), enc(s.type()), enc(s.payload()),
                    joinList(s.dependsOn()), Long.toString(s.runAt()));
            case Event.Started s -> join("A", enc(s.id()), Integer.toString(s.attempt()));
            case Event.Succeeded s -> join("O", enc(s.id()));
            case Event.Failed f -> join("F", enc(f.id()), Integer.toString(f.attempt()), enc(f.error()),
                    Long.toString(f.nextRunAt()));
            case Event.Died d -> join("D", enc(d.id()), Integer.toString(d.attempt()), enc(d.error()));
            case Event.Cancelled c -> join("C", enc(c.id()), enc(c.reason()));
        };
        return String.format("%08x %s", crc(body), body);
    }

    static Event decode(String line) {
        if (line.length() < 10 || line.charAt(8) != ' ') {
            throw new CorruptJournalException("malformed line: " + abbreviate(line));
        }
        String body = line.substring(9);
        long expected;
        try {
            expected = Long.parseLong(line.substring(0, 8), 16);
        } catch (NumberFormatException ex) {
            throw new CorruptJournalException("bad checksum field: " + abbreviate(line));
        }
        if (expected != crc(body)) {
            throw new CorruptJournalException("checksum mismatch: " + abbreviate(line));
        }
        String[] f = body.split("\t", -1);
        try {
            return switch (f[0] + f.length) {
                case "S6" -> new Event.Submitted(dec(f[1]), dec(f[2]), dec(f[3]), splitList(f[4]),
                        Long.parseLong(f[5]));
                case "A3" -> new Event.Started(dec(f[1]), Integer.parseInt(f[2]));
                case "O2" -> new Event.Succeeded(dec(f[1]));
                case "F5" -> new Event.Failed(dec(f[1]), Integer.parseInt(f[2]), dec(f[3]), Long.parseLong(f[4]));
                case "D4" -> new Event.Died(dec(f[1]), Integer.parseInt(f[2]), dec(f[3]));
                case "C3" -> new Event.Cancelled(dec(f[1]), dec(f[2]));
                default -> throw new CorruptJournalException("unknown record: " + abbreviate(line));
            };
        } catch (CorruptJournalException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) { // includes NumberFormatException and bad percent-escapes
            throw new CorruptJournalException("bad field in record: " + abbreviate(line));
        }
    }

    private static long crc(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Joins already-encoded fields with tabs. */
    private static String join(String... fields) {
        return String.join("\t", fields);
    }

    private static String joinList(List<String> items) {
        List<String> encoded = new ArrayList<>();
        for (String s : items) {
            encoded.add(enc(s));
        }
        return String.join(",", encoded);
    }

    private static List<String> splitList(String field) {
        List<String> out = new ArrayList<>();
        if (!field.isEmpty()) {
            for (String part : field.split(",", -1)) {
                out.add(dec(part));
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
