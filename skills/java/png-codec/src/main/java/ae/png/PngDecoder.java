package ae.png;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Strict decoder for non-interlaced PNG files. All defects raise IllegalArgumentException with a descriptive message. */
public final class PngDecoder {
    private PngDecoder() {}

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(msg);
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static Png decode(byte[] data) {
        if (data.length < 8 || !Arrays.equals(Arrays.copyOf(data, 8), PngEncoder.SIGNATURE)) {
            throw bad("bad signature");
        }
        int pos = 8;
        int width = 0, height = 0, depth = 0, colorType = 0;
        int[] palette = null;
        boolean sawIhdr = false, sawIdat = false, idatClosed = false, sawIend = false;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int chunkNo = 0;
        while (!sawIend) {
            if (data.length - pos < 12) throw bad("truncated chunk header");
            long len = getInt(data, pos) & 0xFFFFFFFFL;
            if (len > data.length - pos - 12) throw bad("truncated chunk (length " + len + ")");
            String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 4; i++) {
                char ch = type.charAt(i);
                if (!(ch >= 'A' && ch <= 'Z') && !(ch >= 'a' && ch <= 'z')) throw bad("bad chunk type");
            }
            int dataStart = pos + 8;
            int n = (int) len;
            CRC32 crc = new CRC32();
            crc.update(data, pos + 4, 4 + n);
            if ((int) crc.getValue() != getInt(data, dataStart + n)) throw bad("bad CRC in " + type);
            if (chunkNo == 0 && !type.equals("IHDR")) throw bad("IHDR must be the first chunk");
            chunkNo++;
            if (type.equals("IDAT")) {
                if (!sawIhdr) throw bad("IDAT before IHDR");
                if (idatClosed) throw bad("non-contiguous IDAT");
                sawIdat = true;
                idat.write(data, dataStart, n);
            } else {
                if (sawIdat) idatClosed = true;
                switch (type) {
                    case "IHDR" -> {
                        if (sawIhdr) throw bad("duplicate IHDR");
                        if (n != 13) throw bad("IHDR length " + n);
                        sawIhdr = true;
                        long w = getInt(data, dataStart) & 0xFFFFFFFFL;
                        long h = getInt(data, dataStart + 4) & 0xFFFFFFFFL;
                        depth = data[dataStart + 8] & 0xFF;
                        colorType = data[dataStart + 9] & 0xFF;
                        if (w < 1 || h < 1 || w > Png.MAX_DIM || h > Png.MAX_DIM || w * h > Png.MAX_PIXELS) {
                            throw bad("bad dimensions " + w + "x" + h);
                        }
                        width = (int) w;
                        height = (int) h;
                        if (!Png.validDepth(colorType, depth)) throw bad("illegal colour type/bit depth " + colorType + "/" + depth);
                        if (data[dataStart + 10] != 0) throw bad("unknown compression method");
                        if (data[dataStart + 11] != 0) throw bad("unknown filter method");
                        if (data[dataStart + 12] == 1) throw bad("interlace is not supported");
                        if (data[dataStart + 12] != 0) throw bad("unknown interlace method");
                    }
                    case "PLTE" -> {
                        if (palette != null) throw bad("duplicate PLTE");
                        if (sawIdat) throw bad("PLTE after IDAT");
                        if (colorType == 0 || colorType == 4) throw bad("PLTE not allowed for colour type " + colorType);
                        if (n % 3 != 0 || n == 0) throw bad("PLTE length " + n + " not a positive multiple of 3");
                        if (n / 3 > (1 << depth) && colorType == 3) throw bad("PLTE has too many entries");
                        if (n / 3 > 256) throw bad("PLTE has too many entries");
                        palette = new int[n / 3];
                        for (int i = 0; i < palette.length; i++) {
                            palette[i] = ((data[dataStart + 3 * i] & 0xFF) << 16)
                                    | ((data[dataStart + 3 * i + 1] & 0xFF) << 8)
                                    | (data[dataStart + 3 * i + 2] & 0xFF);
                        }
                    }
                    case "IEND" -> {
                        if (n != 0) throw bad("IEND must be empty");
                        sawIend = true;
                    }
                    default -> {
                        if (Character.isUpperCase(type.charAt(0))) throw bad("unknown critical chunk " + type);
                    }
                }
            }
            pos = dataStart + n + 4;
        }
        if (pos != data.length) throw bad("data after IEND");
        if (!sawIdat) throw bad("missing IDAT");
        if (colorType == 3 && palette == null) throw bad("missing PLTE");
        if (colorType != 3) palette = null; // a (legal) suggested palette on truecolour images is ignored

        int rb = (int) (((long) width * Png.channels(colorType) * depth + 7) / 8);
        int bpp = Math.max(1, Png.channels(colorType) * depth / 8);
        byte[] raw = inflate(idat.toByteArray(), (long) height * (rb + 1));

        int perRow = width * Png.channels(colorType);
        int[] samples = new int[height * perRow];
        byte[] prior = new byte[rb];
        for (int y = 0; y < height; y++) {
            int off = y * (rb + 1);
            int ft = raw[off] & 0xFF;
            if (ft > 4) throw bad("bad filter type byte " + ft + " in row " + y);
            byte[] row = PngFilter.unfilter(ft, Arrays.copyOfRange(raw, off + 1, off + 1 + rb), prior, bpp);
            for (int i = 0; i < perRow; i++) {
                int v;
                if (depth == 8) {
                    v = row[i] & 0xFF;
                } else {
                    int bit = i * depth;
                    v = ((row[bit >> 3] & 0xFF) >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
                }
                samples[y * perRow + i] = v;
            }
            prior = row;
        }
        if (colorType == 3) {
            for (int s : samples) if (s >= palette.length) throw bad("palette index " + s + " out of range");
        }
        return new Png(width, height, colorType, depth, samples, palette);
    }

    /** Inflates exactly {@code expected} bytes; fewer or more is an error. */
    private static byte[] inflate(byte[] z, long expected) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            byte[] out = new byte[(int) expected];
            int got = 0;
            while (got < out.length) {
                int k = inf.inflate(out, got, out.length - got);
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    throw bad("image data too short: " + got + " of " + expected + " bytes");
                }
                got += k;
            }
            byte[] probe = new byte[1];
            if (inf.inflate(probe) != 0 || !inf.finished()) {
                throw bad("image data too long or stream not terminated");
            }
            if (inf.getRemaining() != 0) throw bad("trailing bytes after zlib stream");
            return out;
        } catch (DataFormatException e) {
            throw bad("corrupt zlib stream: " + e.getMessage());
        } finally {
            inf.end();
        }
    }
}
