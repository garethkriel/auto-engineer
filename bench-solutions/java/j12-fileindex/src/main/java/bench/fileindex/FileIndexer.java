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

/** Builds an inverted word index over .txt files below a directory. */
public final class FileIndexer {
    private FileIndexer() {}

    public static Map<String, Set<Path>> index(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().endsWith(".txt")
                            && Files.isRegularFile(p))
                    .toList();
        }
        Map<String, Set<Path>> index = new HashMap<>();
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String w : words(text)) {
                index.computeIfAbsent(w, k -> new HashSet<>()).add(file);
            }
        }
        return index;
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            } else if (sb.length() > 0) {
                out.add(sb.toString().toLowerCase(Locale.ROOT));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
