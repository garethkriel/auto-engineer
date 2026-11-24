package ae.columnar;

/** Independent bit-by-bit packer used only to build crafted images (does not call production code). */
final class BitPackingForTests {
    private BitPackingForTests() {}

    static long[] packLoose(long[] values, int width) {
        int words = (int) (((long) values.length * width + 63) / 64);
        long[] out = new long[words];
        for (int i = 0; i < values.length; i++) {
            for (int b = 0; b < width; b++) {
                if ((values[i] >>> b & 1L) != 0) {
                    int pos = i * width + b;
                    out[pos / 64] |= 1L << (pos % 64);
                }
            }
        }
        return out;
    }
}
