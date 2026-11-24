package ae.columnar;

/** Fixed-width unsigned bit packing into {@code long} words, little-endian bit order, values may straddle words. */
public final class BitPacking {
    private BitPacking() {}

    /** Smallest width (0..64) that holds {@code unsignedMax} interpreted as an unsigned 64-bit number. */
    public static int widthFor(long unsignedMax) {
        return 64 - Long.numberOfLeadingZeros(unsignedMax);
    }

    /** Number of words needed for {@code n} values of {@code width} bits. */
    public static int wordsFor(int n, int width) {
        return (int) (((long) n * width + 63) >>> 6);
    }

    /** Packs unsigned values; every value must fit in {@code width} bits. Padding bits stay zero. */
    public static long[] pack(long[] values, int width) {
        if (width < 0 || width > 64) {
            throw new IllegalArgumentException("width " + width);
        }
        long[] words = new long[wordsFor(values.length, width)];
        for (int i = 0; i < values.length; i++) {
            long v = values[i];
            if (width < 64 && (v >>> width) != 0) {
                throw new IllegalArgumentException("value " + v + " does not fit in " + width + " bits");
            }
            if (width == 0) {
                continue;
            }
            long bit = (long) i * width;
            int w = (int) (bit >>> 6);
            int off = (int) (bit & 63);
            words[w] |= v << off;
            if (off + width > 64) {
                words[w + 1] |= v >>> (64 - off);
            }
        }
        return words;
    }

    /** Reads the {@code i}-th value. */
    public static long get(long[] words, int width, int i) {
        if (width == 0) {
            return 0;
        }
        long bit = (long) i * width;
        int w = (int) (bit >>> 6);
        int off = (int) (bit & 63);
        long v = words[w] >>> off;
        if (off + width > 64) {
            v |= words[w + 1] << (64 - off);
        }
        return width == 64 ? v : v & ((1L << width) - 1);
    }
}
