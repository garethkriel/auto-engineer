package ae.hash;

import java.util.Objects;

/** Keccak-f[1600] sponge: SHA-3 (FIPS 202) fixed-length hashes and SHAKE extendable-output functions. */
public final class Keccak implements Digest {
    private static final long[] RC = new long[24];
    private static final int[] ROT = new int[25];
    private static final int[] PI = new int[25];

    static {
        // Round constants from the degree-8 LFSR x^8+x^6+x^5+x^4+1 (feedback 0x71 when bit 7 is set).
        int r = 1;
        for (int i = 0; i < 24; i++) {
            long c = 0;
            for (int j = 0; j < 7; j++) {
                if ((r & 1) != 0) {
                    c |= 1L << ((1 << j) - 1);
                }
                r <<= 1;
                if ((r & 0x100) != 0) {
                    r ^= 0x171;
                }
            }
            RC[i] = c;
        }
        // Lane index is x + 5y. Rho offsets walk (x,y) -> (y, 2x+3y) with triangular numbers.
        int x = 1;
        int y = 0;
        for (int t = 0; t < 24; t++) {
            ROT[x + 5 * y] = ((t + 1) * (t + 2) / 2) % 64;
            int nx = y;
            y = (2 * x + 3 * y) % 5;
            x = nx;
        }
        // Pi: lane (x,y) moves to (y, 2x+3y).
        for (x = 0; x < 5; x++) {
            for (y = 0; y < 5; y++) {
                PI[x + 5 * y] = y + 5 * ((2 * x + 3 * y) % 5);
            }
        }
    }

    private final long[] state = new long[25];
    private final byte[] buf;
    private final int rate;
    private final int outLen;
    private final byte suffix;
    private final boolean xof;
    private int pos;
    private boolean squeezing;
    private int squeezePos;

    private Keccak(int rate, int outLen, int suffix, boolean xof) {
        this.rate = rate;
        this.outLen = outLen;
        this.suffix = (byte) suffix;
        this.xof = xof;
        this.buf = new byte[rate];
    }

    public static Keccak sha3_224() { return new Keccak(144, 28, 0x06, false); }
    public static Keccak sha3_256() { return new Keccak(136, 32, 0x06, false); }
    public static Keccak sha3_384() { return new Keccak(104, 48, 0x06, false); }
    public static Keccak sha3_512() { return new Keccak(72, 64, 0x06, false); }
    public static Keccak shake128() { return new Keccak(168, 32, 0x1f, true); }
    public static Keccak shake256() { return new Keccak(136, 64, 0x1f, true); }

    @Override public int digestLength() { return outLen; }

    @Override public int blockLength() { return rate; }

    @Override
    public void update(byte[] data, int off, int len) {
        Objects.requireNonNull(data);
        Objects.checkFromIndexSize(off, len, data.length);
        if (squeezing) {
            throw new IllegalStateException("already squeezing; reset() first");
        }
        while (len > 0) {
            int n = Math.min(len, rate - pos);
            System.arraycopy(data, off, buf, pos, n);
            pos += n;
            off += n;
            len -= n;
            if (pos == rate) {
                absorbBlock();
                pos = 0;
            }
        }
    }

    @Override
    public byte[] digest() {
        byte[] out = xof ? squeeze(outLen) : finishFixed();
        reset();
        return out;
    }

    /** Extendable output: returns the next {@code n} bytes of the stream (SHAKE only). */
    public byte[] squeeze(int n) {
        if (!xof) {
            throw new IllegalStateException("fixed-length hash is not an XOF");
        }
        if (n < 0) {
            throw new IllegalArgumentException("negative length");
        }
        if (!squeezing) {
            pad();
            squeezing = true;
            squeezePos = 0;
        }
        byte[] out = new byte[n];
        int done = 0;
        while (done < n) {
            if (squeezePos == rate) {
                keccakF();
                squeezePos = 0;
            }
            int take = Math.min(n - done, rate - squeezePos);
            for (int i = 0; i < take; i++) {
                int k = squeezePos + i;
                out[done + i] = (byte) (state[k >>> 3] >>> (8 * (k & 7)));
            }
            squeezePos += take;
            done += take;
        }
        return out;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(state, 0L);
        java.util.Arrays.fill(buf, (byte) 0);
        pos = 0;
        squeezing = false;
        squeezePos = 0;
    }

    private byte[] finishFixed() {
        pad();
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = (byte) (state[i >>> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    /** pad10*1 with the domain suffix, absorb the last block. */
    private void pad() {
        java.util.Arrays.fill(buf, pos, rate, (byte) 0);
        buf[pos] ^= suffix;
        buf[rate - 1] ^= (byte) 0x80;
        absorbBlock();
        pos = 0;
    }

    private void absorbBlock() {
        for (int i = 0; i < rate / 8; i++) {
            long lane = 0;
            for (int b = 7; b >= 0; b--) {
                lane = lane << 8 | (buf[8 * i + b] & 0xffL);
            }
            state[i] ^= lane;
        }
        keccakF();
    }

    private void keccakF() {
        long[] a = state;
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) {
                    a[x + y] ^= d;
                }
            }
            for (int i = 0; i < 25; i++) {
                b[PI[i]] = Long.rotateLeft(a[i], ROT[i]);
            }
            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    a[x + y] = b[x + y] ^ (~b[(x + 1) % 5 + y] & b[(x + 2) % 5 + y]);
                }
            }
            a[0] ^= RC[round];
        }
    }
}
