package ae.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** One small file per branch, replaced atomically. */
public final class FileRefs implements Refs {
    private final Path dir;

    public FileRefs(Path dir) {
        this.dir = Objects.requireNonNull(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized Optional<ObjectId> get(String name) {
        Refs.checkName(name);
        try {
            String text = Files.readString(dir.resolve(name), StandardCharsets.US_ASCII);
            try {
                return Optional.of(ObjectId.parse(text));
            } catch (IllegalArgumentException e) {
                throw new CorruptObjectException("bad ref file for " + name);
            }
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void set(String name, ObjectId id) {
        Refs.checkName(name);
        Objects.requireNonNull(id);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, ".tmp-", ".part");
            Files.writeString(tmp, id.hex() + "\n", StandardCharsets.US_ASCII);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    @Override
    public synchronized List<String> names() {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
