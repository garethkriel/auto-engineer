package ae.png;

import java.util.Arrays;

/**
 * A decoded PNG: one int per sample, row-major, channels interleaved, top row first.
 * Sample index = (y*width + x)*channels + c. Palette entries are 0xRRGGBB.
 */
public record Png(int width, int height, int colorType, int bitDepth, int[] samples, int[] palette) {
    public static final int MAX_DIM = 16384;
    public static final long MAX_PIXELS = 4_000_000L;

    public Png {
        if (!validDepth(colorType, bitDepth)) {
            throw new IllegalArgumentException("illegal colour type/bit depth " + colorType + "/" + bitDepth);
        }
        if (width < 1 || height < 1 || width > MAX_DIM || height > MAX_DIM || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (samples.length != width * height * channels(colorType)) {
            throw new IllegalArgumentException("sample count mismatch");
        }
        int max = (1 << bitDepth) - 1;
        if (colorType == 3) {
            if (palette == null || palette.length < 1 || palette.length > (1 << bitDepth)) {
                throw new IllegalArgumentException("bad palette size");
            }
            for (int rgb : palette) {
                if ((rgb >>> 24) != 0) throw new IllegalArgumentException("palette entry not 0xRRGGBB");
            }
            for (int s : samples) {
                if (s < 0 || s >= palette.length) throw new IllegalArgumentException("palette index out of range: " + s);
            }
            palette = palette.clone();
        } else {
            if (palette != null) throw new IllegalArgumentException("palette only for colour type 3");
            for (int s : samples) {
                if (s < 0 || s > max) throw new IllegalArgumentException("sample out of range: " + s);
            }
        }
        samples = samples.clone();
    }

    public static boolean validDepth(int colorType, int depth) {
        return switch (colorType) {
            case 0, 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8;
            default -> false;
        };
    }

    public static int channels(int colorType) {
        return switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new IllegalArgumentException("bad colour type " + colorType);
        };
    }

    /** Bytes in one packed scanline (without the filter byte). */
    public int rowBytes() {
        return (int) (((long) width * channels(colorType) * bitDepth + 7) / 8);
    }

    /** Filter distance: bytes per complete pixel, at least 1. */
    public int bpp() {
        return Math.max(1, channels(colorType) * bitDepth / 8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Png p && width == p.width && height == p.height && colorType == p.colorType
                && bitDepth == p.bitDepth && Arrays.equals(samples, p.samples) && Arrays.equals(palette, p.palette);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(samples) * 31 + Arrays.hashCode(palette) + width * 7 + height * 13 + colorType + bitDepth;
    }

    @Override
    public String toString() {
        return "Png[" + width + "x" + height + " type=" + colorType + " depth=" + bitDepth + "]";
    }
}
