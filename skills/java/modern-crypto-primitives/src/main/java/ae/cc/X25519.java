package ae.cc;

import java.math.BigInteger;

/** X25519 Diffie-Hellman, RFC 7748, Montgomery ladder over BigInteger (not constant time). */
public final class X25519 {
    private X25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    /** The base point u = 9 as 32 bytes. */
    static byte[] basePoint() {
        byte[] b = new byte[32];
        b[0] = 9;
        return b;
    }

    public static byte[] scalarMult(byte[] k, byte[] u) {
        Bytes.require(k, 32, "scalar");
        Bytes.require(u, 32, "u");
        byte[] kc = k.clone();
        kc[0] &= (byte) 248;
        kc[31] &= 127;
        kc[31] |= 64;
        BigInteger scalar = Bytes.fromLe(kc, 0, 32);
        byte[] uc = u.clone();
        uc[31] &= 127; // the top bit of u is ignored
        BigInteger x1 = Bytes.fromLe(uc, 0, 32).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        boolean swap = false;
        for (int t = 254; t >= 0; t--) {
            boolean bit = scalar.testBit(t);
            if (swap ^ bit) {
                BigInteger tx = x2, tz = z2;
                x2 = x3; z2 = z3; x3 = tx; z3 = tz;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P), aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P), bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P), d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P), cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P), df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap) {
            x2 = x3; z2 = z3;
        }
        BigInteger out = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P);
        return Bytes.toLe(out, 32);
    }

    public static byte[] publicKey(byte[] secret) {
        return scalarMult(secret, basePoint());
    }

    /** Shared secret; rejects the all-zero result produced by low-order peer points. */
    public static byte[] agree(byte[] secret, byte[] peerPublic) {
        byte[] out = scalarMult(secret, peerPublic);
        int acc = 0;
        for (byte b : out) acc |= b;
        if (acc == 0) {
            throw new IllegalArgumentException("peer public key has low order");
        }
        return out;
    }
}
