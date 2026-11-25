package ae.png;

/** The five PNG scanline filters (None, Sub, Up, Average, Paeth) on unpacked byte rows. */
public final class PngFilter {
    private PngFilter() {}

    /** The Paeth predictor; ties break in the order a, b, c. */
    static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    private static void checkType(int type) {
        if (type < 0 || type > 4) throw new IllegalArgumentException("bad filter type " + type);
    }

    /** Predictor value for byte {@code i} given the already reconstructed row {@code cur}. */
    private static int predict(int type, byte[] cur, byte[] prior, int bpp, int i) {
        int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
        int b = prior[i] & 0xFF;
        int c = i >= bpp ? prior[i - bpp] & 0xFF : 0;
        return switch (type) {
            case 0 -> 0;
            case 1 -> a;
            case 2 -> b;
            case 3 -> (a + b) / 2;
            default -> paeth(a, b, c);
        };
    }

    /** Filters {@code row}; {@code prior} is the previous raw row (all zero for the first row). */
    public static byte[] filter(int type, byte[] row, byte[] prior, int bpp) {
        checkType(type);
        byte[] out = new byte[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = (byte) (row[i] - predict(type, row, prior, bpp, i));
        }
        return out;
    }

    /** Reverses {@link #filter}: {@code prior} is the previous reconstructed row. */
    public static byte[] unfilter(int type, byte[] filtered, byte[] prior, int bpp) {
        checkType(type);
        byte[] out = new byte[filtered.length];
        for (int i = 0; i < filtered.length; i++) {
            out[i] = (byte) (filtered[i] + predict(type, out, prior, bpp, i));
        }
        return out;
    }
}
