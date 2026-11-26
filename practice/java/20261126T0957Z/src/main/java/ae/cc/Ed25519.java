package ae.cc;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Ed25519, RFC 8032 section 5.1, over BigInteger with extended coordinates (X:Y:Z:T). SHA-512 comes from the JDK.
 * Verification is strict: S must be below L, and A and R must be canonical encodings of curve points.
 */
public final class Ed25519 {
    private Ed25519() {}

    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** Extended homogeneous point; x = X/Z, y = Y/Z, xy = T/Z. */
    private record Pt(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {}

    private static final Pt IDENTITY = new Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    private static final Pt BASE;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, false);
        BASE = new Pt(bx, by, BigInteger.ONE, bx.multiply(by).mod(P));
    }

    /** x for a given y and sign bit, or null if y is not on the curve. */
    private static BigInteger recoverX(BigInteger y, boolean sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        // candidate x = u v^3 (u v^7)^((p-5)/8)
        BigInteger v3 = v.pow(3).mod(P);
        BigInteger v7 = v.pow(7).mod(P);
        BigInteger x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vx2 = v.multiply(x).multiply(x).mod(P);
        if (!vx2.equals(u)) {
            if (!vx2.equals(u.negate().mod(P))) {
                return null;
            }
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.signum() == 0 && sign) {
            return null;
        }
        if (x.testBit(0) != sign) {
            x = P.subtract(x);
        }
        return x;
    }

    private static Pt add(Pt p, Pt q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = BigInteger.TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P);
        BigInteger d = BigInteger.TWO.multiply(p.z).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    private static Pt mul(BigInteger s, Pt p) {
        Pt q = IDENTITY;
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (s.testBit(i)) {
                q = add(q, p);
            }
        }
        return q;
    }

    private static boolean equal(Pt p, Pt q) {
        return p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0
                && p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0;
    }

    private static byte[] encode(Pt p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P), y = p.y.multiply(zi).mod(P);
        byte[] out = Bytes.toLe(y, 32);
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /** Strict decode; null for non-canonical y (>= p) or a y that is not on the curve. */
    private static Pt decode(byte[] enc, int off) {
        byte[] c = Arrays.copyOfRange(enc, off, off + 32);
        boolean sign = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = Bytes.fromLe(c, 0, 32);
        if (y.compareTo(P) >= 0) {
            return null;
        }
        BigInteger x = recoverX(y, sign);
        if (x == null) {
            return null;
        }
        return new Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger clampedScalar(byte[] h) {
        byte[] a = Arrays.copyOf(h, 32);
        a[0] &= (byte) 248;
        a[31] &= 127;
        a[31] |= 64;
        return Bytes.fromLe(a, 0, 32);
    }

    public static byte[] publicKey(byte[] seed) {
        Bytes.require(seed, 32, "seed");
        return encode(mul(clampedScalar(sha512(seed)), BASE));
    }

    public static byte[] sign(byte[] seed, byte[] msg) {
        Bytes.require(seed, 32, "seed");
        Objects.requireNonNull(msg, "msg");
        byte[] h = sha512(seed);
        BigInteger a = clampedScalar(h);
        byte[] pub = encode(mul(a, BASE));
        BigInteger r = Bytes.fromLe(sha512(Arrays.copyOfRange(h, 32, 64), msg), 0, 64).mod(L);
        byte[] rEnc = encode(mul(r, BASE));
        BigInteger k = Bytes.fromLe(sha512(rEnc, pub, msg), 0, 64).mod(L);
        BigInteger s = r.add(k.multiply(a)).mod(L);
        byte[] sig = new byte[64];
        System.arraycopy(rEnc, 0, sig, 0, 32);
        System.arraycopy(Bytes.toLe(s, 32), 0, sig, 32, 32);
        return sig;
    }

    /** Strict verification; returns false (never throws) for anything malformed. */
    public static boolean verify(byte[] pub, byte[] msg, byte[] sig) {
        Objects.requireNonNull(msg, "msg");
        if (pub == null || sig == null || pub.length != 32 || sig.length != 64) {
            return false;
        }
        Pt a = decode(pub, 0);
        Pt r = decode(sig, 0);
        if (a == null || r == null) {
            return false;
        }
        BigInteger s = Bytes.fromLe(sig, 32, 32);
        if (s.compareTo(L) >= 0) {
            return false;
        }
        BigInteger k = Bytes.fromLe(sha512(Arrays.copyOfRange(sig, 0, 32), pub, msg), 0, 64).mod(L);
        return equal(mul(s, BASE), add(r, mul(k, a)));
    }
}
