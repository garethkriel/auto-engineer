package kv;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * String-to-string store persisted as an append-only journal.
 *
 * <p>Lines are {@code S<TAB>key<TAB>value} and {@code D<TAB>key}; text is escaped so that
 * tabs, newlines, carriage returns and backslashes never break the line format. Not thread-safe.
 */
public final class FileKvStore {
    private final Path file;
    private final Map<String, String> data = new TreeMap<>();

    private FileKvStore(Path file) {
        this.file = file;
    }

    /** Opens (creating if needed) the journal at {@code file} and replays it. */
    public static FileKvStore open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        FileKvStore store = new FileKvStore(file);
        if (!Files.exists(file)) {
            Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW);
            return store;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int lastNewline = text.lastIndexOf('\n');
        String complete = text.substring(0, lastNewline + 1);
        int lineNo = 0;
        int pos = 0;
        while (pos < complete.length()) {
            int end = complete.indexOf('\n', pos);
            lineNo++;
            store.replay(complete.substring(pos, end), lineNo);
            pos = end + 1;
        }
        if (lastNewline + 1 < text.length()) {
            // torn write: drop the partial trailing line
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(complete.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return store;
    }

    private void replay(String line, int lineNo) throws IOException {
        String[] parts = line.split("\t", -1);
        try {
            if (parts[0].equals("S") && parts.length == 3) {
                data.put(unescape(parts[1]), unescape(parts[2]));
            } else if (parts[0].equals("D") && parts.length == 2) {
                data.remove(unescape(parts[1]));
            } else {
                throw new IllegalArgumentException("unrecognised record");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("corrupt journal " + file + " at line " + lineNo + ": " + e.getMessage(), e);
        }
    }

    /** Stores {@code value} under {@code key}. */
    public void put(String key, String value) throws IOException {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        append("S\t" + escape(key) + "\t" + escape(value) + "\n");
        data.put(key, value);
    }

    /** Removes {@code key}; returns whether it was present. Absent keys write nothing. */
    public boolean remove(String key) throws IOException {
        Objects.requireNonNull(key, "key");
        if (!data.containsKey(key)) {
            return false;
        }
        append("D\t" + escape(key) + "\n");
        data.remove(key);
        return true;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(Objects.requireNonNull(key, "key")));
    }

    public int size() {
        return data.size();
    }

    /** Live keys in sorted order. */
    public List<String> keys() {
        return List.copyOf(new ArrayList<>(data.keySet()));
    }

    /** Rewrites the journal with one record per live key, via temp file and atomic move. */
    public void compact() throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "kv-compact", ".tmp");
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : data.entrySet()) {
                sb.append("S\t").append(escape(e.getKey())).append('\t').append(escape(e.getValue())).append('\n');
            }
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
    }

    private void append(String record) throws IOException {
        Files.write(file, record.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i == s.length()) {
                throw new IllegalArgumentException("dangling escape");
            }
            switch (s.charAt(i)) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> throw new IllegalArgumentException("bad escape \\" + s.charAt(i));
            }
        }
        return sb.toString();
    }
}
