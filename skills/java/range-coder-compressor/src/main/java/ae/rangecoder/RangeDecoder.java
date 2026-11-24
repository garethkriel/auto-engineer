package ae.rangecoder;

/** Decoder matching {@link RangeEncoder}. {@code code} is kept relative to {@code low}, so carries never reach it. */
public final class RangeDecoder {
    private final byte[] data;
    private int pos;
    private long range = RangeEncoder.MASK32;
    private long code;
    private long step;

    /**
     * @param data   coder bytes
     * @param offset index of the first coder byte
     * @throws CodecException if fewer than 5 bytes are available or the leading byte is not zero
     */
    public RangeDecoder(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
        if (nextByte() != 0) throw new CodecException("bad stream start");
        for (int i = 0; i < 4; i++) code = (code << 8) | nextByte();
    }

    /**
     * First half of frequency decoding: returns the point in {@code [0,total)} that identifies the symbol.
     * Must be followed by {@link #consume} with the symbol's interval.
     */
    public int target(int total) {
        if (total < 1 || total > RangeEncoder.MAX_TOTAL) throw new IllegalArgumentException("total " + total);
        step = range / total;
        long t = code / step;
        if (t >= total) throw new CodecException("target outside range");
        return (int) t;
    }

    /** Second half of frequency decoding. */
    public void consume(int cum, int freq, int total) {
        if (freq < 1 || cum < 0 || cum + freq > total) throw new IllegalArgumentException("interval");
        code -= (long) cum * step;
        range = (long) freq * step;
        normalise();
    }

    /** Decodes one bit with an adaptive model and updates the model. */
    public int decodeBit(BitModel m) {
        long bound = (range >>> BitModel.BITS) * m.probZero();
        int bit;
        if (code < bound) {
            range = bound;
            bit = 0;
        } else {
            code -= bound;
            range -= bound;
            bit = 1;
        }
        m.update(bit);
        normalise();
        return bit;
    }

    private void normalise() {
        while (range < RangeEncoder.TOP) {
            range = (range << 8) & RangeEncoder.MASK32;
            code = ((code << 8) | nextByte()) & RangeEncoder.MASK32;
        }
    }

    private int nextByte() {
        if (pos >= data.length) throw new CodecException("unexpected end of data");
        return data[pos++] & 0xFF;
    }
}
