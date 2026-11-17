package bench.csv;

import java.util.ArrayList;
import java.util.List;

public final class CsvParser {
    private CsvParser() {}

    public static List<String> parseLine(String line) {
        if (line == null) throw new IllegalArgumentException("null");
        List<String> out = new ArrayList<>();
        int n = line.length(), i = 0;
        while (true) {
            StringBuilder sb = new StringBuilder();
            if (i < n && line.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && line.charAt(i + 1) == '"') { sb.append('"'); i += 2; }
                        else { closed = true; i++; break; }
                    } else { sb.append(c); i++; }
                }
                if (!closed) throw new IllegalArgumentException("unterminated quote");
                if (i < n && line.charAt(i) != ',') throw new IllegalArgumentException("text after quote");
            } else {
                while (i < n && line.charAt(i) != ',') {
                    if (line.charAt(i) == '"') throw new IllegalArgumentException("quote in unquoted field");
                    sb.append(line.charAt(i++));
                }
            }
            out.add(sb.toString());
            if (i >= n) break;
            i++; // comma
            if (i == n) { out.add(""); break; }
        }
        return out;
    }
}
