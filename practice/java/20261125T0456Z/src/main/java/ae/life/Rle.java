package ae.life;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Run-length-encoded Life patterns (the common ".rle" format), rule B3/S23 only. */
public final class Rle {
    private Rle() { }

    /** Cells are relative to the top-left corner of the header's box and sorted by row, then column. */
    public record Pattern(List<Cell> cells, int width, int height) { }

    public static Pattern parse(String text) {
        String header = null;
        StringBuilder body = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (header == null) {
                if (!line.startsWith("x")) {
                    throw new IllegalArgumentException("missing header line 'x = ..., y = ...'");
                }
                header = line;
            } else {
                body.append(line);
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("missing header line");
        }
        int width = -1;
        int height = -1;
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("bad header field '" + part.trim() + "'");
            }
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String val = kv[1].trim();
            switch (key) {
                case "x" -> width = parseInt(val);
                case "y" -> height = parseInt(val);
                case "rule" -> checkRule(val);
                default -> throw new IllegalArgumentException("unknown header key '" + key + "'");
            }
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("header needs x and y");
        }
        List<Cell> cells = new ArrayList<>();
        int x = 0;
        int y = 0;
        int run = -1; // -1: no digits seen
        boolean ended = false;
        String s = body.toString();
        for (int i = 0; i < s.length() && !ended; i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                run = (run < 0 ? 0 : run) * 10 + (ch - '0');
                if (run > 1_000_000) {
                    throw new IllegalArgumentException("run too long");
                }
                continue;
            }
            if (ch == ' ' || ch == '\t') {
                continue;
            }
            if (run == 0) {
                throw new IllegalArgumentException("run of zero before '" + ch + "'");
            }
            int count = run < 0 ? 1 : run;
            switch (ch) {
                case 'b' -> x += count;
                case 'o' -> {
                    if (x + count > width) {
                        throw new IllegalArgumentException("pattern wider than width " + width);
                    }
                    if (y >= height) {
                        throw new IllegalArgumentException("pattern taller than height " + height);
                    }
                    for (int k = 0; k < count; k++) {
                        cells.add(new Cell(x + k, y));
                    }
                    x += count;
                }
                case '$' -> {
                    y += count;
                    x = 0;
                }
                case '!' -> {
                    if (run >= 0) {
                        throw new IllegalArgumentException("dangling run count before '!'");
                    }
                    ended = true;
                }
                default -> throw new IllegalArgumentException("unexpected character '" + ch + "'");
            }
            run = -1;
        }
        if (!ended) {
            throw new IllegalArgumentException("missing terminating '!'");
        }
        Collections.sort(cells);
        return new Pattern(List.copyOf(cells), width, height);
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number '" + v + "'");
        }
    }

    private static void checkRule(String v) {
        String r = v.toLowerCase(Locale.ROOT).replace(" ", "");
        if (!r.equals("b3/s23") && !r.equals("23/3")) {
            throw new IllegalArgumentException("unsupported rule '" + v + "'");
        }
    }

    /** Writes cells normalised to their bounding box, lines at most 70 characters. */
    public static String write(Collection<Cell> cells) {
        List<Cell> sorted = new ArrayList<>(cells);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return "x = 0, y = 0, rule = B3/S23\n!\n";
        }
        long minX = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        for (Cell c : sorted) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
        }
        long minY = sorted.get(0).y();
        long maxY = sorted.get(sorted.size() - 1).y();
        List<String> tokens = new ArrayList<>();
        long row = minY;
        long col = 0;
        int i = 0;
        while (i < sorted.size()) {
            Cell c = sorted.get(i);
            if (c.y() != row) {
                tokens.add(token(c.y() - row, '$'));
                row = c.y();
                col = 0;
            }
            long x = c.x() - minX;
            if (x > col) {
                tokens.add(token(x - col, 'b'));
                col = x;
            }
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1).y() == row && sorted.get(j + 1).x() == sorted.get(j).x() + 1) {
                j++;
            }
            tokens.add(token(j - i + 1, 'o'));
            col += j - i + 1;
            i = j + 1;
        }
        tokens.add("!");
        StringBuilder out = new StringBuilder("x = " + (maxX - minX + 1) + ", y = " + (maxY - minY + 1) + ", rule = B3/S23\n");
        int lineLen = 0;
        for (String t : tokens) {
            if (lineLen + t.length() > 70) {
                out.append('\n');
                lineLen = 0;
            }
            out.append(t);
            lineLen += t.length();
        }
        return out.append('\n').toString();
    }

    private static String token(long count, char symbol) {
        return count == 1 ? String.valueOf(symbol) : count + String.valueOf(symbol);
    }
}
