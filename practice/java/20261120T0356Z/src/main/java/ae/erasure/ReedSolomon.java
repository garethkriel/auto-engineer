package ae.erasure;

import java.util.Arrays;

/** Systematic Cauchy Reed-Solomon erasure code over GF(256): k data shards, m parity shards. */
public final class ReedSolomon {
    private final int k;
    private final int m;
    private final GfMatrix encoding; // (k+m) x k, top k rows identity

    public ReedSolomon(int k, int m) {
        if (k < 1 || m < 1 || k + m > 256) {
            throw new IllegalArgumentException("need 1<=k, 1<=m, k+m<=256, got k=" + k + " m=" + m);
        }
        this.k = k;
        this.m = m;
        byte[][] e = new byte[k + m][k];
        for (int i = 0; i < k; i++) {
            e[i][i] = 1;
        }
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < k; j++) {
                e[k + i][j] = (byte) Gf256.inv((k + i) ^ j);
            }
        }
        this.encoding = new GfMatrix(e);
    }

    public int dataShards() {
        return k;
    }

    public int parityShards() {
        return m;
    }

    /** Computes the m parity shards of k equal-length data shards. */
    public byte[][] encode(byte[][] data) {
        if (data.length != k) {
            throw new IllegalArgumentException("expected " + k + " data shards, got " + data.length);
        }
        int len = data[0].length;
        for (byte[] d : data) {
            if (d.length != len) {
                throw new IllegalArgumentException("shards differ in length");
            }
        }
        byte[][] parity = new byte[m][len];
        for (int i = 0; i < m; i++) {
            combine(encoding.row(k + i), data, parity[i]);
        }
        return parity;
    }

    /** Recomputes parity from the data shards and compares it with the stored parity. */
    public boolean verify(byte[][] shards) {
        checkShape(shards, null);
        byte[][] data = Arrays.copyOfRange(shards, 0, k);
        byte[][] parity = encode(data);
        for (int i = 0; i < m; i++) {
            if (!Arrays.equals(parity[i], shards[k + i])) {
                return false;
            }
        }
        return true;
    }

    /** Fills every shard flagged absent in {@code present}; needs at least k present shards. */
    public void reconstruct(byte[][] shards, boolean[] present) {
        checkShape(shards, present);
        int n = k + m;
        int[] use = new int[k];
        int count = 0;
        int len = -1;
        for (int i = 0; i < n; i++) {
            if (present[i]) {
                if (len >= 0 && shards[i].length != len) {
                    throw new IllegalArgumentException("present shards differ in length");
                }
                len = shards[i].length;
                if (count < k) {
                    use[count++] = i;
                }
            }
        }
        if (count < k) {
            throw new IllegalArgumentException("need " + k + " shards, only " + count + " present");
        }
        GfMatrix decode = encoding.subMatrix(use).invert();
        byte[][] source = new byte[k][];
        for (int i = 0; i < k; i++) {
            source[i] = shards[use[i]];
        }
        byte[][] data = new byte[k][len];
        for (int i = 0; i < k; i++) {
            combine(decode.row(i), source, data[i]);
        }
        for (int i = 0; i < n; i++) {
            if (!present[i]) {
                byte[] out = new byte[len];
                if (i < k) {
                    out = data[i];
                } else {
                    combine(encoding.row(i), data, out);
                }
                shards[i] = out;
            }
        }
    }

    private void checkShape(byte[][] shards, boolean[] present) {
        if (shards.length != k + m) {
            throw new IllegalArgumentException("expected " + (k + m) + " shards, got " + shards.length);
        }
        if (present != null && present.length != shards.length) {
            throw new IllegalArgumentException("present[] has wrong length");
        }
        int len = -1;
        for (int i = 0; i < shards.length; i++) {
            if (present != null && !present[i]) {
                continue;
            }
            if (shards[i] == null) {
                throw new IllegalArgumentException("shard " + i + " is null");
            }
            if (len >= 0 && shards[i].length != len) {
                throw new IllegalArgumentException("shards differ in length");
            }
            len = shards[i].length;
        }
    }

    private static void combine(byte[] coefficients, byte[][] shards, byte[] out) {
        for (int j = 0; j < shards.length; j++) {
            int c = coefficients[j] & 0xFF;
            if (c == 0) {
                continue;
            }
            byte[] s = shards[j];
            for (int b = 0; b < out.length; b++) {
                out[b] ^= (byte) Gf256.mul(c, s[b]);
            }
        }
    }
}
