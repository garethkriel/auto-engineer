package ae.pk;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A short-Weierstrass curve y^2 = x^3 + a x + b over GF(p) with prime group order n and cofactor 1.
 * Only primes with p = 3 (mod 4) are supported (square roots are a single exponentiation).
 * Arithmetic uses Jacobian coordinates; none of it is constant time.
 */
public final class Curve {
    /** NIST P-256 (secp256r1). */
    public static final Curve P256 = new Curve("P-256",
        hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"),
        hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"),
        hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"),
        hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),
        hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
        hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"));

    /** secp256k1. */
    public static final Curve SECP256K1 = new Curve("secp256k1",
        hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F"),
        BigInteger.ZERO,
        BigInteger.valueOf(7),
        hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141"),
        hex("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"),
        hex("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8"));

    private final String name;
    private final BigInteger p;
    private final BigInteger a;
    private final BigInteger b;
    private final BigInteger n;
    private final Point g;
    private final int fieldBytes;

    /**
     * Creates a curve and validates it: p = 3 (mod 4), G on the curve, n*G = infinity.
     * (Primality of p and n is the caller's responsibility.)
     */
    public Curve(String name, BigInteger p, BigInteger a, BigInteger b, BigInteger n, BigInteger gx, BigInteger gy) {
        this.name = Objects.requireNonNull(name);
        if (p.mod(BigInteger.valueOf(4)).intValue() != 3) {
            throw new IllegalArgumentException("p must be 3 mod 4");
        }
        this.p = p;
        this.a = a.mod(p);
        this.b = b.mod(p);
        this.n = n;
        this.g = new Point(gx, gy);
        this.fieldBytes = (p.bitLength() + 7) / 8;
        if (!onCurve(gx, gy)) {
            throw new IllegalArgumentException("generator is not on the curve");
        }
        if (!mul(n, g).isInfinity()) {
            throw new IllegalArgumentException("n*G is not the identity");
        }
    }

    private static BigInteger hex(String s) {
        return new BigInteger(s, 16);
    }

    public String name() { return name; }
    public BigInteger p() { return p; }
    public BigInteger a() { return a; }
    public BigInteger b() { return b; }
    public BigInteger n() { return n; }
    public Point g() { return g; }
    /** Byte length of a field element. */
    public int fieldBytes() { return fieldBytes; }

    /** True when 0 <= x, y < p and (x, y) satisfies the curve equation. */
    public boolean onCurve(BigInteger x, BigInteger y) {
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger rhs = x.pow(3).add(a.multiply(x)).add(b).mod(p);
        return y.multiply(y).mod(p).equals(rhs);
    }

    /** True for the identity or a point satisfying {@link #onCurve}. */
    public boolean isValid(Point q) {
        return q.isInfinity() || onCurve(q.x(), q.y());
    }

    /** Group addition. */
    public Point add(Point p1, Point p2) {
        return fromJacobian(jAdd(toJacobian(p1), toJacobian(p2)));
    }

    /** Scalar multiplication k*P for k >= 0 (left-to-right double and add). */
    public Point mul(BigInteger k, Point pt) {
        if (k.signum() < 0) {
            throw new IllegalArgumentException("negative scalar");
        }
        BigInteger[] base = toJacobian(pt);
        BigInteger[] acc = toJacobian(Point.INFINITY);
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            acc = jDouble(acc);
            if (k.testBit(i)) {
                acc = jAdd(acc, base);
            }
        }
        return fromJacobian(acc);
    }

    /** a*P + b*Q. */
    public Point mulAdd(BigInteger k1, Point p1, BigInteger k2, Point p2) {
        return add(mul(k1, p1), mul(k2, p2));
    }

    // ---- SEC1 encoding ----

    /** SEC1 encoding of a finite point: 04|x|y or 02/03|x. */
    public byte[] encode(Point pt, boolean compressed) {
        if (pt.isInfinity()) {
            throw new IllegalArgumentException("cannot encode infinity");
        }
        byte[] out = new byte[compressed ? 1 + fieldBytes : 1 + 2 * fieldBytes];
        System.arraycopy(i2osp(pt.x(), fieldBytes), 0, out, 1, fieldBytes);
        if (compressed) {
            out[0] = (byte) (pt.y().testBit(0) ? 3 : 2);
        } else {
            out[0] = 4;
            System.arraycopy(i2osp(pt.y(), fieldBytes), 0, out, 1 + fieldBytes, fieldBytes);
        }
        return out;
    }

    /** Strict SEC1 decoding; rejects infinity, hybrid forms, coordinates >= p, off-curve points. */
    public Point decode(byte[] enc) {
        if (enc.length == 1 + 2 * fieldBytes && enc[0] == 4) {
            BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(enc, 1, 1 + fieldBytes));
            BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(enc, 1 + fieldBytes, enc.length));
            if (!onCurve(x, y)) {
                throw new IllegalArgumentException("point is not on the curve");
            }
            return new Point(x, y);
        }
        if (enc.length == 1 + fieldBytes && (enc[0] == 2 || enc[0] == 3)) {
            BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(enc, 1, enc.length));
            if (x.compareTo(p) >= 0) {
                throw new IllegalArgumentException("x >= p");
            }
            BigInteger rhs = x.pow(3).add(a.multiply(x)).add(b).mod(p);
            BigInteger y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p);
            if (!y.multiply(y).mod(p).equals(rhs)) {
                throw new IllegalArgumentException("x has no square root on the curve");
            }
            boolean wantOdd = enc[0] == 3;
            if (y.testBit(0) != wantOdd) {
                y = p.subtract(y);
            }
            if (y.testBit(0) != wantOdd) { // y == 0 cannot flip parity
                throw new IllegalArgumentException("parity cannot be matched");
            }
            return new Point(x, y);
        }
        throw new IllegalArgumentException("unsupported point encoding");
    }

    /** Big-endian unsigned encoding of v in exactly len bytes. */
    public static byte[] i2osp(BigInteger v, int len) {
        if (v.signum() < 0) {
            throw new IllegalArgumentException("negative integer");
        }
        byte[] raw = v.toByteArray();
        int skip = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        int have = raw.length - skip;
        if (have > len) {
            throw new IllegalArgumentException("integer too large for " + len + " bytes");
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, skip, out, len - have, have);
        return out;
    }

    // ---- Jacobian arithmetic: (X, Y, Z) represents (X/Z^2, Y/Z^3); Z = 0 is infinity ----

    private BigInteger[] toJacobian(Point pt) {
        if (pt.isInfinity()) {
            return new BigInteger[] {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        }
        return new BigInteger[] {pt.x(), pt.y(), BigInteger.ONE};
    }

    private Point fromJacobian(BigInteger[] j) {
        if (j[2].signum() == 0) {
            return Point.INFINITY;
        }
        BigInteger zi = j[2].modInverse(p);
        BigInteger zi2 = zi.multiply(zi).mod(p);
        return new Point(j[0].multiply(zi2).mod(p), j[1].multiply(zi2).multiply(zi).mod(p));
    }

    private BigInteger[] jDouble(BigInteger[] j) {
        if (j[2].signum() == 0 || j[1].signum() == 0) {
            return toJacobian(Point.INFINITY);
        }
        BigInteger x = j[0];
        BigInteger y = j[1];
        BigInteger z = j[2];
        BigInteger y2 = y.multiply(y).mod(p);
        BigInteger s = x.multiply(y2).shiftLeft(2).mod(p);
        BigInteger z2 = z.multiply(z).mod(p);
        BigInteger m = x.multiply(x).multiply(BigInteger.valueOf(3)).add(a.multiply(z2.multiply(z2))).mod(p);
        BigInteger x3 = m.multiply(m).subtract(s.shiftLeft(1)).mod(p);
        BigInteger y3 = m.multiply(s.subtract(x3)).subtract(y2.multiply(y2).shiftLeft(3)).mod(p);
        BigInteger z3 = y.multiply(z).shiftLeft(1).mod(p);
        return new BigInteger[] {x3, y3, z3};
    }

    private BigInteger[] jAdd(BigInteger[] p1, BigInteger[] p2) {
        if (p1[2].signum() == 0) {
            return p2;
        }
        if (p2[2].signum() == 0) {
            return p1;
        }
        BigInteger z1s = p1[2].multiply(p1[2]).mod(p);
        BigInteger z2s = p2[2].multiply(p2[2]).mod(p);
        BigInteger u1 = p1[0].multiply(z2s).mod(p);
        BigInteger u2 = p2[0].multiply(z1s).mod(p);
        BigInteger s1 = p1[1].multiply(z2s).multiply(p2[2]).mod(p);
        BigInteger s2 = p2[1].multiply(z1s).multiply(p1[2]).mod(p);
        if (u1.equals(u2)) {
            return s1.equals(s2) ? jDouble(p1) : toJacobian(Point.INFINITY);
        }
        BigInteger h = u2.subtract(u1).mod(p);
        BigInteger r = s2.subtract(s1).mod(p);
        BigInteger h2 = h.multiply(h).mod(p);
        BigInteger h3 = h2.multiply(h).mod(p);
        BigInteger v = u1.multiply(h2).mod(p);
        BigInteger x3 = r.multiply(r).subtract(h3).subtract(v.shiftLeft(1)).mod(p);
        BigInteger y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(h3)).mod(p);
        BigInteger z3 = h.multiply(p1[2]).multiply(p2[2]).mod(p);
        return new BigInteger[] {x3, y3, z3};
    }
}
