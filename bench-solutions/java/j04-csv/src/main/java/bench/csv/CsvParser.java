package bench.csv;

import java.util.ArrayList;
import java.util.List;

/** Single-line CSV parser. */
public final class CsvParser {
    private CsvParser() { }

    public static List<String> parseLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("null line");
        }
        List<String> out = new ArrayList<>();
        int n = line.length();
        int i = 0;
        while (true) {
            StringBuilder f = new StringBuilder();
            if (i < n && line.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && line.charAt(i + 1) == '"') {
                            f.append('"');
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        f.append(c);
                        i++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated quote");
                }
                if (i < n && line.charAt(i) != ',') {
                    throw new IllegalArgumentException("text after closing quote");
                }
            } else {
                while (i < n && line.charAt(i) != ',') {
                    if (line.charAt(i) == '"') {
                        throw new IllegalArgumentException("quote in unquoted field");
                    }
                    f.append(line.charAt(i++));
                }
            }
            out.add(f.toString());
            if (i >= n) {
                return out;
            }
            i++; // skip comma
        }
    }
}
