package bench.csv;

import java.util.ArrayList;
import java.util.List;

/** Parses one CSV line. */
public final class CsvParser {
    private CsvParser() { }

    public static List<String> parseLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("null line");
        }
        List<String> fields = new ArrayList<>();
        int n = line.length();
        int i = 0;
        while (true) {
            StringBuilder sb = new StringBuilder();
            if (i < n && line.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated quoted field");
                }
                if (i < n && line.charAt(i) != ',') {
                    throw new IllegalArgumentException("text after closing quote");
                }
            } else {
                while (i < n && line.charAt(i) != ',') {
                    char c = line.charAt(i);
                    if (c == '"') {
                        throw new IllegalArgumentException("quote in unquoted field");
                    }
                    sb.append(c);
                    i++;
                }
            }
            fields.add(sb.toString());
            if (i >= n) {
                return fields;
            }
            i++; // skip comma; a trailing comma yields a final empty field
        }
    }
}
