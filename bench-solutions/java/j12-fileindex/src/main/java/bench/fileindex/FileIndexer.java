package bench.fileindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Word index of .txt files. */
public final class FileIndexer {
    private FileIndexer() { }

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .toList();
        }
        Map<String, Set<Path>> idx = new HashMap<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            StringBuilder w = new StringBuilder();
            int i = 0;
            while (i <= text.length()) {
                int cp = i < text.length() ? text.codePointAt(i) : -1;
                if (cp >= 0 && Character.isLetterOrDigit(cp)) {
                    w.appendCodePoint(cp);
                } else if (w.length() > 0) {
                    idx.computeIfAbsent(w.toString().toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(f);
                    w.setLength(0);
                }
                i += cp < 0 ? 1 : Character.charCount(cp);
            }
        }
        return idx;
    }
}
