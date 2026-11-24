package bench.fileindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class FileIndexer {
    private FileIndexer() {}

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) throw new IllegalArgumentException("not a directory: " + dir);
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".txt"))
                     .toList();
        }
        Map<String, Set<Path>> result = new HashMap<>();
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetterOrDigit(cp)) {
                    sb.appendCodePoint(cp);
                } else if (sb.length() > 0) {
                    add(result, sb, p);
                }
            }
            if (sb.length() > 0) add(result, sb, p);
        }
        return result;
    }

    private static void add(Map<String, Set<Path>> m, StringBuilder sb, Path p) {
        String w = sb.toString().toLowerCase(Locale.ROOT);
        sb.setLength(0);
        m.computeIfAbsent(w, k -> new HashSet<>()).add(p);
    }
}
