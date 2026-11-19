package ae.deflate;

/** zlib container (RFC 1950) around DEFLATE: 2-byte header, payload, big-endian Adler-32. */
public final class Zlib {
    private Zlib() {}

    public static byte[] wrap(byte[] data, Deflate.Level level) {
        byte[] body = Deflate.compress(data, level);
        int flevel = switch (level) {
            case STORED, FAST -> 0;
            case DEFAULT -> 2;
            case BEST -> 3;
        };
        int cmf = 0x78;
        int flg = flevel << 6;
        flg += 31 - ((cmf << 8) + flg) % 31;
        byte[] out = new byte[2 + body.length + 4];
        out[0] = (byte) cmf;
        out[1] = (byte) flg;
        System.arraycopy(body, 0, out, 2, body.length);
        long adler = Adler32.of(data);
        for (int i = 0; i < 4; i++) {
            out[out.length - 1 - i] = (byte) (adler >>> (8 * i));
        }
        return out;
    }

    public static byte[] unwrap(byte[] in, int maxOutput) {
        if (in.length < 2) {
            throw new InflateException("truncated zlib header");
        }
        int cmf = in[0] & 0xFF;
        int flg = in[1] & 0xFF;
        if ((cmf & 0x0F) != 8) {
            throw new InflateException("unsupported compression method " + (cmf & 0x0F));
        }
        if ((cmf >> 4) > 7) {
            throw new InflateException("invalid window size");
        }
        if (((cmf << 8) | flg) % 31 != 0) {
            throw new InflateException("header check bits invalid");
        }
        if ((flg & 0x20) != 0) {
            throw new InflateException("preset dictionary not supported");
        }
        Inflate.Result r = Inflate.inflate(in, 2, maxOutput);
        int end = 2 + r.consumed();
        if (in.length - end < 4) {
            throw new InflateException("truncated Adler-32 trailer");
        }
        if (in.length - end > 4) {
            throw new InflateException("trailing data after zlib stream");
        }
        long stored = 0;
        for (int i = 0; i < 4; i++) {
            stored = (stored << 8) | (in[end + i] & 0xFF);
        }
        if (stored != Adler32.of(r.data())) {
            throw new InflateException("Adler-32 mismatch");
        }
        return r.data();
    }
}
