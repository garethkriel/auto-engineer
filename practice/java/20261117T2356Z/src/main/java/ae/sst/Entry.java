package ae.sst;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A key with a value; a {@code null} value is a tombstone (deletion marker).
 * Keys are ordered by unsigned UTF-8 bytes, see {@link #compareKeys}.
 */
public record Entry(String key, byte[] value) {
    public static final int MAX_KEY_BYTES = 65535;

    public Entry {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) throw new IllegalArgumentException("empty key");
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key longer than " + MAX_KEY_BYTES + " UTF-8 bytes");
        }
        value = value == null ? null : value.clone();
    }

    /** The stored value (defensive copy), or null for a tombstone. */
    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    public boolean isTombstone() {
        return value == null;
    }

    /** Unsigned lexicographic comparison of the UTF-8 encodings (differs from String.compareTo for surrogates). */
    public static int compareKeys(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Entry e && key.equals(e.key) && Arrays.equals(value, e.value);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Entry[" + key + (value == null ? ", tombstone]" : ", " + value.length + " bytes]");
    }
}
