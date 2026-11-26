package ae.arch;

import java.util.Arrays;
import java.util.Objects;

/**
 * A file or directory member. A name ending in {@code /} is a directory and has no data.
 * The data array is copied on the way in and on the way out.
 */
public record ArchiveEntry(String name, byte[] data) {
    public ArchiveEntry {
        Names.validate(name);
        Objects.requireNonNull(data, "data");
        if (name.endsWith("/") && data.length > 0) {
            throw new IllegalArgumentException("directory with data: " + name);
        }
        data = data.clone();
    }

    public boolean directory() {
        return name.endsWith("/");
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArchiveEntry e && name.equals(e.name) && Arrays.equals(data, e.data);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ArchiveEntry[" + name + ", " + data.length + " bytes]";
    }
}
