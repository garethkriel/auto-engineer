package rl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Size-based rotating log file: {@code file}, {@code file.1} (newest backup) ... {@code file.N}. */
public final class RotatingLog {
    private final Path file;
    private final long maxBytes;
    private final int maxBackups;
    private long size;

    public RotatingLog(Path file, long maxBytes, int maxBackups) {
        Objects.requireNonNull(file, "file");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be >= 1");
        if (maxBackups < 0) throw new IllegalArgumentException("maxBackups must be >= 0");
        this.file = file;
        this.maxBytes = maxBytes;
        this.maxBackups = maxBackups;
        try {
            this.size = Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends one line, rotating first if it would not fit. */
    public void append(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line breaks");
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (size > 0 && size + bytes.length > maxBytes) {
                rotate();
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size += bytes.length;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long currentSize() {
        return size;
    }

    /** Existing backup files, newest (.1) first. */
    public List<Path> backups() {
        List<Path> result = new ArrayList<>();
        for (int i = 1; i <= maxBackups; i++) {
            Path p = backup(i);
            if (Files.exists(p)) result.add(p);
        }
        return result;
    }

    private Path backup(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    private void rotate() throws IOException {
        if (maxBackups == 0) {
            Files.write(file, new byte[0]);
        } else {
            Files.deleteIfExists(backup(maxBackups));
            for (int i = maxBackups - 1; i >= 1; i--) {
                if (Files.exists(backup(i))) {
                    Files.move(backup(i), backup(i + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(file, backup(1), StandardCopyOption.REPLACE_EXISTING);
        }
        size = 0;
    }
}
