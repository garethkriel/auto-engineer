package ae.torrent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Bencode dictionary: keys are byte strings kept in unsigned-byte order. */
public final class BDict implements BValue {
    private final TreeMap<BStr, BValue> map;

    private BDict(TreeMap<BStr, BValue> map) {
        this.map = map;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BValue get(String key) {
        return map.get(BStr.of(key));
    }

    public BValue get(BStr key) {
        return map.get(key);
    }

    public int size() {
        return map.size();
    }

    /** Entries in ascending unsigned-byte key order (read-only view). */
    public Set<Map.Entry<BStr, BValue>> entries() {
        return Collections.unmodifiableMap(map).entrySet();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BDict d && map.equals(d.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return "BDict" + map;
    }

    /** Collects entries; a later {@code put} of the same key replaces the earlier one. */
    public static final class Builder {
        private final TreeMap<BStr, BValue> map = new TreeMap<>();

        public Builder put(String key, BValue value) {
            return put(BStr.of(key), value);
        }

        public Builder put(BStr key, BValue value) {
            map.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
            return this;
        }

        public BDict build() {
            return new BDict(new TreeMap<>(map));
        }
    }
}
