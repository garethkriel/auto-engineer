package ae.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Loose-object directory adapter: {@code root/ab/cdef...}. Writes go temp file then atomic move. */
public final class FileObjectStore implements ObjectStore {
    private final Path root;

    public FileObjectStore(Path root) {
        this.root = Objects.requireNonNull(root);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path pathOf(ObjectId id) {
        return root.resolve(id.hex().substring(0, 2)).resolve(id.hex().substring(2));
    }

    @Override
    public ObjectId put(byte[] raw) {
        Objects.requireNonNull(raw);
        ObjectId id = ObjectId.ofRaw(raw);
        Path target = pathOf(id);
        if (Files.exists(target)) {
            return id;
        }
        Path dir = target.getParent();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".tmp-", ".part");
            Files.write(tmp, raw, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup; the original failure is already propagating
                }
            }
        }
    }

    @Override
    public Optional<byte[]> get(ObjectId id) {
        try {
            byte[] raw = Files.readAllBytes(pathOf(id));
            if (!ObjectId.ofRaw(raw).equals(id)) {
                throw new CorruptObjectException("hash mismatch for " + id);
            }
            return Optional.of(raw);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
