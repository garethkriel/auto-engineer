package ae.columnar;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.CRC32;

/** Test helper that hand-builds serialised images in the documented layout. */
final class Img {
    private Img() {}

    static byte[] seal(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return concat(body, intBytes((int) crc.getValue()));
    }

    static byte[] intBytes(int v) {
        return build(o -> o.writeInt(v));
    }

    static byte[] longBytes(long v) {
        return build(o -> o.writeLong(v));
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    interface Writer {
        void write(DataOutputStream o) throws IOException;
    }

    static byte[] build(Writer w) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bos)) {
            w.write(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static byte[] header(int n, int enc) {
        return build(o -> {
            o.writeInt(n);
            o.writeByte(enc);
        });
    }

    static byte[] plain(long... values) {
        return build(o -> {
            o.writeInt(values.length);
            o.writeByte(Encoding.PLAIN.ordinal());
            for (long v : values) {
                o.writeLong(v);
            }
        });
    }

    static byte[] rle(int n, long[] values, int[] lengths) {
        return build(o -> {
            o.writeInt(n);
            o.writeByte(Encoding.RLE.ordinal());
            o.writeInt(values.length);
            for (int i = 0; i < values.length; i++) {
                o.writeLong(values[i]);
                o.writeInt(lengths[i]);
            }
        });
    }

    static byte[] dict(int n, long[] dict, int width, long... words) {
        return build(o -> {
            o.writeInt(n);
            o.writeByte(Encoding.DICT.ordinal());
            o.writeInt(dict.length);
            for (long d : dict) {
                o.writeLong(d);
            }
            o.writeByte(width);
            for (long w : words) {
                o.writeLong(w);
            }
        });
    }

    static byte[] frameOfReference(int n, long base, int width, long... words) {
        return build(o -> {
            o.writeInt(n);
            o.writeByte(Encoding.FOR.ordinal());
            o.writeLong(base);
            o.writeByte(width);
            for (long w : words) {
                o.writeLong(w);
            }
        });
    }

    static byte[] delta(int n, long first, int width, long[] zigzagDeltas, long... extraWords) {
        long[] packed = BitPackingForTests.packLoose(zigzagDeltas, width);
        return build(o -> {
            o.writeInt(n);
            o.writeByte(Encoding.DELTA.ordinal());
            o.writeLong(first);
            o.writeByte(width);
            for (int i = 0; i < packed.length; i++) {
                o.writeLong(extraWords.length > 0 && i == 0 ? packed[i] | extraWords[0] : packed[i]);
            }
        });
    }
}
