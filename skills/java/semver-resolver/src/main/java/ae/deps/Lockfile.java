package ae.deps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/** Text lockfile: one {@code name@version} line per package, sorted by name. */
public final class Lockfile {
    private Lockfile() {}

    /** Renders the resolution; every line ends with '\n'. */
    public static String format(Resolution r) {
        StringBuilder sb = new StringBuilder();
        r.versions().forEach((n, v) -> sb.append(n).append('@').append(v).append('\n'));
        return sb.toString();
    }

    /** Parses lock text (comments with '#', blank lines allowed); duplicate names are rejected. */
    public static Map<String, Version> parse(String text) {
        Map<String, Version> out = new TreeMap<>();
        for (String raw : text.split("\n", -1)) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty()) continue;
            int at = line.indexOf('@');
            if (at < 0) throw new IllegalArgumentException("bad lock line '" + line + "'");
            String name = line.substring(0, at);
            Package.checkName(name);
            if (out.put(name, Version.parse(line.substring(at + 1))) != null) {
                throw new IllegalArgumentException("duplicate lock entry " + name);
            }
        }
        return out;
    }

    /** Writes via a temp file in the same directory and an atomic move. */
    public static void write(Path file, Resolution r) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "lock", ".tmp");
        try {
            Files.writeString(tmp, format(r), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Reads a lockfile written by {@link #write}. */
    public static Map<String, Version> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }
}
