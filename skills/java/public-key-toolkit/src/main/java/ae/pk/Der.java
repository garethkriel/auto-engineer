package ae.pk;

import java.math.BigInteger;

/** Strict DER codec for the ECDSA-Sig-Value SEQUENCE { INTEGER r, INTEGER s }. */
public final class Der {
    private Der() { }

    /** Minimal DER encoding; r and s must be positive. */
    public static byte[] encode(Ecdsa.Signature sig) {
        if (sig.r().signum() <= 0 || sig.s().signum() <= 0) {
            throw new IllegalArgumentException("r and s must be positive");
        }
        byte[] r = sig.r().toByteArray();
        byte[] s = sig.s().toByteArray();
        int body = 2 + r.length + 2 + s.length;
        if (body > 127) {
            throw new IllegalArgumentException("signature too large for short-form DER");
        }
        byte[] out = new byte[2 + body];
        out[0] = 0x30;
        out[1] = (byte) body;
        out[2] = 2;
        out[3] = (byte) r.length;
        System.arraycopy(r, 0, out, 4, r.length);
        out[4 + r.length] = 2;
        out[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, out, 6 + r.length, s.length);
        return out;
    }

    /** Strict decode: exact length, short-form lengths, positive minimal integers, nothing trailing. */
    public static Ecdsa.Signature decode(byte[] der) {
        if (der.length < 2 || der[0] != 0x30) {
            throw new IllegalArgumentException("expected SEQUENCE");
        }
        int len = der[1] & 0xff;
        if (len >= 128) {
            throw new IllegalArgumentException("long-form length not allowed");
        }
        if (len != der.length - 2) {
            throw new IllegalArgumentException("sequence length mismatch");
        }
        int[] pos = {2};
        BigInteger r = readInt(der, pos);
        BigInteger s = readInt(der, pos);
        if (pos[0] != der.length) {
            throw new IllegalArgumentException("trailing data in sequence");
        }
        return new Ecdsa.Signature(r, s);
    }

    private static BigInteger readInt(byte[] der, int[] pos) {
        int i = pos[0];
        if (i + 2 > der.length || der[i] != 2) {
            throw new IllegalArgumentException("expected INTEGER");
        }
        int len = der[i + 1] & 0xff;
        if (len == 0 || len >= 128 || i + 2 + len > der.length) {
            throw new IllegalArgumentException("bad INTEGER length");
        }
        byte first = der[i + 2];
        if ((first & 0x80) != 0) {
            throw new IllegalArgumentException("negative INTEGER");
        }
        if (len > 1 && first == 0 && (der[i + 3] & 0x80) == 0) {
            throw new IllegalArgumentException("non-minimal INTEGER");
        }
        pos[0] = i + 2 + len;
        return new BigInteger(java.util.Arrays.copyOfRange(der, i + 2, i + 2 + len));
    }
}
