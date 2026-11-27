package ae.xsort;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;

/** Ready-made codecs. */
public final class Codecs {
    private Codecs() {}

    /** Longest string payload accepted when reading, so corrupt lengths cannot trigger huge allocations. */
    static final int MAX_STRING_BYTES = 1 << 28;

    /** Strings as an int byte length followed by UTF-8 bytes (no 64K limit, unlike writeUTF). */
    public static final Codec<String> STRING = new Codec<>() {
        @Override
        public void write(DataOutput out, String value) throws IOException {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(b.length);
            out.write(b);
        }

        @Override
        public String read(DataInput in) throws IOException {
            int n = in.readInt();
            if (n < 0 || n > MAX_STRING_BYTES) throw new StreamCorruptedException("bad string length " + n);
            byte[] b = new byte[n];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }
    };

    public static final Codec<Long> LONG = new Codec<>() {
        @Override
        public void write(DataOutput out, Long value) throws IOException {
            out.writeLong(value);
        }

        @Override
        public Long read(DataInput in) throws IOException {
            return in.readLong();
        }
    };
}
