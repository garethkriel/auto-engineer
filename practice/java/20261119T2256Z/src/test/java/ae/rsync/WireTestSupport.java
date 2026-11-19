package ae.rsync;

import java.util.zip.CRC32;

final class WireTestSupport {
    private WireTestSupport() {}

    /** Recompute the trailing big-endian CRC32 over all bytes before it. */
    static void reseal(byte[] image) {
        CRC32 crc = new CRC32();
        crc.update(image, 0, image.length - 4);
        long v = crc.getValue();
        image[image.length - 4] = (byte) (v >>> 24);
        image[image.length - 3] = (byte) (v >>> 16);
        image[image.length - 2] = (byte) (v >>> 8);
        image[image.length - 1] = (byte) v;
    }
}
