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

/** Builds an inverted word index over .txt files. */
public final class FileIndexer {
    private FileIndexer() {}

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".txt")).toList();
        }
        Map<String, Set<Path>> result = new HashMap<>();
        for (Path f : files) {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            int i = 0;
            int n = text.length();
            while (i < n) {
                int cp = text.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) {
                    int start = i;
                    while (i < n && Character.isLetterOrDigit(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i));
                    }
                    String w = text.substring(start, i).toLowerCase(Locale.ROOT);
                    result.computeIfAbsent(w, k -> new HashSet<>()).add(f);
                } else {
                    i += Character.charCount(cp);
                }
            }
        }
        return result;
    }
}
