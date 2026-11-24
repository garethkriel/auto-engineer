package ae.rangecoder;

/** Adaptive probability that the next bit is 0: 11-bit fixed point, exponential decay with shift 5. */
public final class BitModel {
    /** Probability scale (2^11). */
    public static final int BITS = 11;
    public static final int SCALE = 1 << BITS;
    public static final int ONE_HALF = SCALE / 2;
    static final int SHIFT = 5;

    private int prob = ONE_HALF;

    /** @return current probability of a zero bit, in (0, SCALE) */
    public int probZero() {
        return prob;
    }

    void update(int bit) {
        if (bit == 0) {
            prob += (SCALE - prob) >>> SHIFT;
        } else {
            prob -= prob >>> SHIFT;
        }
    }
}
