package ae.pk;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** ECDSA with RFC 6979 deterministic nonces (HMAC-SHA-256). Inputs are message hashes, not messages. */
public final class Ecdsa {
    private Ecdsa() { }

    /** An ECDSA signature (r, s). */
    public record Signature(BigInteger r, BigInteger s) { }

    /** Public key d*G for a private scalar d in [1, n-1]. */
    public static Point publicKey(Curve c, BigInteger d) {
        checkScalar(c, d);
        return c.mul(d, c.g());
    }

    static void checkScalar(Curve c, BigInteger d) {
        if (d.signum() <= 0 || d.compareTo(c.n()) >= 0) {
            throw new IllegalArgumentException("private key out of range [1, n-1]");
        }
    }

    /** SEC1 bits2int: the leftmost bitLength(n) bits of the hash as an integer. */
    static BigInteger bits2int(Curve c, byte[] hash) {
        BigInteger z = new BigInteger(1, hash);
        int excess = hash.length * 8 - c.n().bitLength();
        return excess > 0 ? z.shiftRight(excess) : z;
    }

    /** Signs a message hash; the nonce follows RFC 6979 so equal inputs give equal signatures. */
    public static Signature sign(Curve c, BigInteger d, byte[] hash) {
        checkScalar(c, d);
        NonceStream ns = new NonceStream(c, d, hash);
        BigInteger z = bits2int(c, hash);
        while (true) {
            BigInteger k = ns.next();
            if (k.signum() <= 0 || k.compareTo(c.n()) >= 0) {
                continue;
            }
            Point kg = c.mul(k, c.g());
            BigInteger r = kg.x().mod(c.n());
            if (r.signum() == 0) {
                continue;
            }
            BigInteger s = k.modInverse(c.n()).multiply(z.add(r.multiply(d))).mod(c.n());
            if (s.signum() == 0) {
                continue;
            }
            return new Signature(r, s);
        }
    }

    /** The first acceptable RFC 6979 nonce for (d, hash). */
    public static BigInteger nonce(Curve c, BigInteger d, byte[] hash) {
        checkScalar(c, d);
        NonceStream ns = new NonceStream(c, d, hash);
        while (true) {
            BigInteger k = ns.next();
            if (k.signum() > 0 && k.compareTo(c.n()) < 0) {
                return k;
            }
        }
    }

    /** Verifies (r, s) over hash against public key q. Out-of-range r or s simply fail. */
    public static boolean verify(Curve c, Point q, byte[] hash, Signature sig) {
        if (q.isInfinity() || !c.onCurve(q.x(), q.y())) {
            throw new IllegalArgumentException("invalid public key");
        }
        BigInteger n = c.n();
        BigInteger r = sig.r();
        BigInteger s = sig.s();
        if (r.signum() <= 0 || r.compareTo(n) >= 0 || s.signum() <= 0 || s.compareTo(n) >= 0) {
            return false;
        }
        BigInteger w = s.modInverse(n);
        BigInteger u1 = bits2int(c, hash).multiply(w).mod(n);
        BigInteger u2 = r.multiply(w).mod(n);
        Point pt = c.mulAdd(u1, c.g(), u2, q);
        return !pt.isInfinity() && pt.x().mod(n).equals(r);
    }

    /** Returns the signature with s replaced by min(s, n - s). */
    public static Signature lowS(Curve c, Signature sig) {
        BigInteger other = c.n().subtract(sig.s());
        return sig.s().compareTo(other) <= 0 ? sig : new Signature(sig.r(), other);
    }

    /** RFC 6979 section 3.2 K/V state machine yielding successive candidate nonces. */
    private static final class NonceStream {
        private final Curve curve;
        private byte[] k;
        private byte[] v;

        NonceStream(Curve curve, BigInteger d, byte[] hash) {
            this.curve = curve;
            int rlen = (curve.n().bitLength() + 7) / 8;
            byte[] x = Curve.i2osp(d, rlen);
            byte[] h = Curve.i2osp(bits2int(curve, hash).mod(curve.n()), rlen);
            v = new byte[32];
            Arrays.fill(v, (byte) 1);
            k = new byte[32];
            k = hmac(k, v, new byte[] {0}, x, h);
            v = hmac(k, v);
            k = hmac(k, v, new byte[] {1}, x, h);
            v = hmac(k, v);
        }

        BigInteger next() {
            int qlen = curve.n().bitLength();
            byte[] t = new byte[0];
            while (t.length * 8 < qlen) {
                v = hmac(k, v);
                t = concat(t, v);
            }
            BigInteger cand = bits2int(curve, t);
            k = hmac(k, v, new byte[] {0});
            v = hmac(k, v);
            return cand;
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] hmac(byte[] key, byte[]... data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] d : data) {
                mac.update(d);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
