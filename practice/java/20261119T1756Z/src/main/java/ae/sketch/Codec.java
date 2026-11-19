package ae.sketch;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Shared framing: 4-byte magic, payload, trailing CRC32 of everything before it. */
final class Codec {
    static final int CRC_SIZE = 4;

    private Codec() {
    }

    static byte[] seal(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(body.length + CRC_SIZE).put(body).putInt((int) crc.getValue()).array();
    }

    /** Verifies magic and checksum; returns a buffer positioned just after the magic, limited to the body. */
    static ByteBuffer open(byte[] image, int magic, int minBody) {
        if (image == null || image.length < 4 + minBody + CRC_SIZE) {
            throw new IllegalArgumentException("image too short");
        }
        int bodyLen = image.length - CRC_SIZE;
        CRC32 crc = new CRC32();
        crc.update(image, 0, bodyLen);
        int stored = ByteBuffer.wrap(image, bodyLen, CRC_SIZE).getInt();
        if ((int) crc.getValue() != stored) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        ByteBuffer buf = ByteBuffer.wrap(image, 0, bodyLen);
        if (buf.getInt() != magic) {
            throw new IllegalArgumentException("bad magic");
        }
        return buf;
    }
}
