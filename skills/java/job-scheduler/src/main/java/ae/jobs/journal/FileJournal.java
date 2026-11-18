package ae.jobs.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * File-backed journal. Each append writes {@code line + '\n'} and forces it to disk. Opening trims a torn
 * tail (bytes after the last newline, left by a crash mid-write) so later appends start on a clean line.
 */
public final class FileJournal implements Journal {
    private final Path path;

    public FileJournal(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        try {
            if (Files.exists(path)) {
                trimTornTail();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void trimTornTail() throws IOException {
        byte[] data = Files.readAllBytes(path);
        int keep = data.length;
        while (keep > 0 && data[keep - 1] != '\n') {
            keep--;
        }
        if (keep != data.length) {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.truncate(keep);
                ch.force(true);
            }
        }
    }

    @Override
    public synchronized void append(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain newlines");
        }
        ByteBuffer buf = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(false);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public synchronized List<String> readAll() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            int start = 0;
            int nl;
            while ((nl = text.indexOf('\n', start)) >= 0) {
                out.add(text.substring(start, nl));
                start = nl + 1;
            }
            // Anything after the last newline is a torn write and is ignored.
            return out;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
