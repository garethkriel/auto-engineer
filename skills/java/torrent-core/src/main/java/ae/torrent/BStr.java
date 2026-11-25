package ae.torrent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Bencode byte string: immutable, compared by content, ordered as unsigned bytes. */
public final class BStr implements BValue, Comparable<BStr> {
    private final byte[] bytes;

    public BStr(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes).clone();
    }

    public static BStr of(String s) {
        return new BStr(s.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    public String utf8() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int compareTo(BStr o) {
        return Arrays.compareUnsigned(bytes, o.bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BStr s && Arrays.equals(bytes, s.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "BStr" + Arrays.toString(bytes);
    }
}
