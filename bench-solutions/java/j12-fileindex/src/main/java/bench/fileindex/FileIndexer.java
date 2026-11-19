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

/** Builds a word to files index over the .txt files below a directory. */
public final class FileIndexer {
    private FileIndexer() {
    }

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> Files.isRegularFile(p)
                    && p.getFileName() != null
                    && p.getFileName().toString().endsWith(".txt")).toList();
        }
        Map<String, Set<Path>> index = new HashMap<>();
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            int i = 0;
            int len = text.length();
            while (i < len) {
                int cp = text.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) {
                    int start = i;
                    while (i < len && Character.isLetterOrDigit(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i));
                    }
                    String word = text.substring(start, i).toLowerCase(Locale.ROOT);
                    index.computeIfAbsent(word, k -> new HashSet<>()).add(file);
                } else {
                    i += Character.charCount(cp);
                }
            }
        }
        return index;
    }
}
