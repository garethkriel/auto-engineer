package ae.proto;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable byte string with value equality, used for {@code bytes} fields and unknown fields. */
public final class Bytes {
    private final byte[] data;

    private Bytes(byte[] data) {
        this.data = data;
    }

    public static Bytes of(byte[] raw) {
        return new Bytes(Objects.requireNonNull(raw, "raw").clone());
    }

    static Bytes slice(byte[] buf, int from, int to) {
        return new Bytes(Arrays.copyOfRange(buf, from, to));
    }

    /** A copy of the content. */
    public byte[] bytes() {
        return data.clone();
    }

    int length() {
        return data.length;
    }

    byte[] raw() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes b && Arrays.equals(data, b.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "0x" + HexFormat.of().formatHex(data);
    }
}
