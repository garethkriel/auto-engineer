package bench.fileindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class FileIndexer {
    private FileIndexer() {}

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) throw new IllegalArgumentException("not a directory");
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".txt")).toList();
        }
        Map<String, Set<Path>> idx = new TreeMap<>();
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i <= text.length()) {
                int cp = i < text.length() ? text.codePointAt(i) : -1;
                if (cp >= 0 && Character.isLetterOrDigit(cp)) {
                    sb.appendCodePoint(cp);
                } else if (sb.length() > 0) {
                    idx.computeIfAbsent(sb.toString().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(p);
                    sb.setLength(0);
                }
                if (cp < 0) break;
                i += Character.charCount(cp);
            }
        }
        return idx;
    }
}
