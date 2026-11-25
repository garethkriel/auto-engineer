package ae.png;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Writes non-interlaced PNG files: IHDR, [PLTE], one IDAT, IEND. */
public final class PngEncoder {
    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private PngEncoder() {}

    public static byte[] encode(Png png, FilterMode mode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, png.width());
        putInt(ihdr, 4, png.height());
        ihdr[8] = (byte) png.bitDepth();
        ihdr[9] = (byte) png.colorType();
        writeChunk(out, "IHDR", ihdr);
        if (png.colorType() == 3) {
            int[] pal = png.palette();
            byte[] plte = new byte[pal.length * 3];
            for (int i = 0; i < pal.length; i++) {
                plte[3 * i] = (byte) (pal[i] >> 16);
                plte[3 * i + 1] = (byte) (pal[i] >> 8);
                plte[3 * i + 2] = (byte) pal[i];
            }
            writeChunk(out, "PLTE", plte);
        }
        writeChunk(out, "IDAT", deflate(filteredScanlines(png, mode)));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Scanlines as stored before compression: each row is a filter-type byte plus the filtered packed row. */
    static byte[] filteredScanlines(Png png, FilterMode mode) {
        int rb = png.rowBytes();
        int bpp = png.bpp();
        byte[] result = new byte[png.height() * (rb + 1)];
        byte[] prior = new byte[rb];
        for (int y = 0; y < png.height(); y++) {
            byte[] row = pack(png, y);
            int bestType;
            byte[] best;
            if (mode == FilterMode.ADAPTIVE) {
                bestType = 0;
                best = PngFilter.filter(0, row, prior, bpp);
                long bestScore = score(best);
                for (int t = 1; t <= 4; t++) {
                    byte[] cand = PngFilter.filter(t, row, prior, bpp);
                    long sc = score(cand);
                    if (sc < bestScore) { // strict: ties keep the lowest type
                        bestScore = sc;
                        bestType = t;
                        best = cand;
                    }
                }
            } else {
                bestType = mode.ordinal();
                best = PngFilter.filter(bestType, row, prior, bpp);
            }
            int off = y * (rb + 1);
            result[off] = (byte) bestType;
            System.arraycopy(best, 0, result, off + 1, rb);
            prior = row;
        }
        return result;
    }

    private static long score(byte[] filtered) {
        long s = 0;
        for (byte b : filtered) s += Math.abs((int) b);
        return s;
    }

    /** Packs row {@code y} of samples at the image's bit depth, MSB first, padding the last byte with zeros. */
    private static byte[] pack(Png png, int y) {
        int perRow = png.width() * Png.channels(png.colorType());
        int depth = png.bitDepth();
        byte[] row = new byte[png.rowBytes()];
        int[] s = png.samples();
        for (int i = 0; i < perRow; i++) {
            int v = s[y * perRow + i];
            if (depth == 8) {
                row[i] = (byte) v;
            } else {
                int bit = i * depth;
                row[bit >> 3] |= (byte) (v << (8 - depth - (bit & 7)));
            }
        }
        return row;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
