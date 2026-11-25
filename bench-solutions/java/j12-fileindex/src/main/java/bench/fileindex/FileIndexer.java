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
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .toList();
        }
        Map<String, Set<Path>> idx = new HashMap<>();
        for (Path f : files) {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetterOrDigit(cp)) {
                    sb.appendCodePoint(cp);
                } else if (sb.length() > 0) {
                    add(idx, sb, f);
                }
            }
            if (sb.length() > 0) add(idx, sb, f);
        }
        return idx;
    }

    private static void add(Map<String, Set<Path>> idx, StringBuilder sb, Path f) {
        idx.computeIfAbsent(sb.toString().toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(f);
        sb.setLength(0);
    }
}
