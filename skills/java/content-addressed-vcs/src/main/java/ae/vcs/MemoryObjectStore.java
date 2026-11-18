package ae.vcs;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory adapter. Thread-safe. */
public final class MemoryObjectStore implements ObjectStore {
    private final Map<ObjectId, byte[]> objects = new HashMap<>();

    @Override
    public synchronized ObjectId put(byte[] raw) {
        Objects.requireNonNull(raw);
        ObjectId id = ObjectId.ofRaw(raw);
        objects.putIfAbsent(id, raw.clone());
        return id;
    }

    @Override
    public synchronized Optional<byte[]> get(ObjectId id) {
        byte[] raw = objects.get(id);
        if (raw == null) {
            return Optional.empty();
        }
        if (!ObjectId.ofRaw(raw).equals(id)) {
            throw new CorruptObjectException("hash mismatch for " + id);
        }
        return Optional.of(raw.clone());
    }

    /** Test hook: replaces stored bytes without rehashing. */
    synchronized void tamper(ObjectId id, byte[] raw) {
        objects.put(id, raw);
    }

    synchronized void remove(ObjectId id) {
        objects.remove(id);
    }
}
