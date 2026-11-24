package ae.rangecoder;

import java.io.ByteArrayOutputStream;

/**
 * LZMA-style range encoder: 33-bit {@code low}, 32-bit {@code range}, and a byte delay
 * ({@code cache}/{@code cacheSize}) so a carry can still ripple into bytes already "emitted".
 */
public final class RangeEncoder {
    static final long TOP = 1L << 24;
    static final long MASK32 = 0xFFFFFFFFL;
    /** Largest {@code total} accepted by {@link #encode}. */
    public static final int MAX_TOTAL = 1 << 16;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long low;
    private long range = MASK32;
    private int cache;
    private long cacheSize = 1;
    private int carries;
    private boolean finished;

    /**
     * Encodes the symbol that owns the interval {@code [cum, cum+freq)} of {@code total}.
     *
     * @throws IllegalArgumentException if the interval is not inside {@code [0,total)} or total is out of range
     * @throws IllegalStateException    after {@link #finish()}
     */
    public void encode(int cum, int freq, int total) {
        ensureOpen();
        if (total < 1 || total > MAX_TOTAL) throw new IllegalArgumentException("total " + total);
        if (freq < 1 || cum < 0 || cum + freq > total) {
            throw new IllegalArgumentException("interval [" + cum + "," + (cum + freq) + ") of " + total);
        }
        long r = range / total;
        low += (long) cum * r;
        range = (long) freq * r;
        normalise();
    }

    /** Encodes one bit with an adaptive model and updates the model. */
    public void encodeBit(BitModel m, int bit) {
        ensureOpen();
        if (bit != 0 && bit != 1) throw new IllegalArgumentException("bit " + bit);
        long bound = (range >>> BitModel.BITS) * m.probZero();
        if (bit == 0) {
            range = bound;
        } else {
            low += bound;
            range -= bound;
        }
        m.update(bit);
        normalise();
    }

    /** Flushes the coder and returns all bytes. The encoder cannot be used afterwards. */
    public byte[] finish() {
        ensureOpen();
        finished = true;
        for (int i = 0; i < 5; i++) shiftLow();
        return out.toByteArray();
    }

    /** @return how many times a carry rippled into delayed bytes (diagnostic) */
    public int carries() {
        return carries;
    }

    private void ensureOpen() {
        if (finished) throw new IllegalStateException("encoder finished");
    }

    private void normalise() {
        while (range < TOP) {
            range = (range << 8) & MASK32;
            shiftLow();
        }
    }

    private void shiftLow() {
        if (low < 0xFF000000L || low > MASK32) {
            int carry = (int) (low >>> 32);
            if (carry != 0) carries++;
            int temp = cache;
            do {
                out.write((temp + carry) & 0xFF);
                temp = 0xFF;
            } while (--cacheSize != 0);
            cache = (int) ((low >>> 24) & 0xFF);
        }
        cacheSize++;
        low = (low & 0x00FFFFFFL) << 8;
    }
}
