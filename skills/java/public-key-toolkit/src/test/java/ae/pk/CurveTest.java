package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CurveTest {
    private static BigInteger h(String s) { return new BigInteger(s.replace(" ", ""), 16); }

    /** Naive affine oracle: textbook formulas with modInverse, double-and-add. */
    static Point naiveAdd(Curve c, Point p, Point q) {
        if (p.isInfinity()) return q;
        if (q.isInfinity()) return p;
        BigInteger m;
        if (p.x().equals(q.x())) {
            if (!p.y().equals(q.y()) || p.y().signum() == 0) return Point.INFINITY;
            m = p.x().pow(2).multiply(BigInteger.valueOf(3)).add(c.a())
                .multiply(p.y().shiftLeft(1).modInverse(c.p())).mod(c.p());
        } else {
            m = q.y().subtract(p.y()).multiply(q.x().subtract(p.x()).mod(c.p()).modInverse(c.p())).mod(c.p());
        }
        BigInteger x = m.pow(2).subtract(p.x()).subtract(q.x()).mod(c.p());
        BigInteger y = m.multiply(p.x().subtract(x)).subtract(p.y()).mod(c.p());
        return new Point(x, y);
    }

    static Point naiveMul(Curve c, BigInteger k, Point p) {
        Point r = Point.INFINITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = naiveAdd(c, r, r);
            if (k.testBit(i)) r = naiveAdd(c, r, p);
        }
        return r;
    }

    @Test
    void p256ConstantsMatchJdk() throws Exception {
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec jdk = ap.getParameterSpec(ECParameterSpec.class);
        Curve c = Curve.P256;
        assertEquals(((java.security.spec.ECFieldFp) jdk.getCurve().getField()).getP(), c.p());
        assertEquals(jdk.getCurve().getA(), c.a().mod(c.p()));
        assertEquals(jdk.getCurve().getB(), c.b());
        assertEquals(jdk.getOrder(), c.n());
        assertEquals(jdk.getGenerator().getAffineX(), c.g().x());
        assertEquals(jdk.getGenerator().getAffineY(), c.g().y());
        assertEquals(1, jdk.getCofactor());
    }

    @Test
    void generatorsAreOnCurveAndHaveOrderN() {
        for (Curve c : new Curve[] {Curve.P256, Curve.SECP256K1}) {
            assertTrue(c.onCurve(c.g().x(), c.g().y()), c.name());
            assertTrue(c.mul(c.n(), c.g()).isInfinity(), c.name() + " n*G");
            Point nm1 = c.mul(c.n().subtract(BigInteger.ONE), c.g());
            assertEquals(c.g().x(), nm1.x(), c.name());
            assertEquals(c.p().subtract(c.g().y()), nm1.y(), c.name() + " (n-1)G = -G");
            assertTrue(c.mul(BigInteger.ZERO, c.g()).isInfinity());
            assertEquals(c.g(), c.mul(BigInteger.ONE, c.g()));
        }
    }

    @Test
    void publishedDoubleOfGenerator() {
        Point p2 = Curve.P256.mul(BigInteger.TWO, Curve.P256.g());
        assertEquals(h("7CF27B18 8D034F7E 8A523803 04B51AC3 C08969E2 77F21B35 A60B48FC 47669978"), p2.x());
        assertEquals(h("07775510 DB8ED040 293D9AC6 9F7430DB BA7DADE6 3CE98229 9E04B79D 227873D1"), p2.y());
        Point k2 = Curve.SECP256K1.mul(BigInteger.TWO, Curve.SECP256K1.g());
        assertEquals(h("C6047F94 41ED7D6D 3045406E 95C07CD8 5C778E4B 8CEF3CA7 ABAC09B9 5C709EE5"), k2.x());
    }

    @Test
    void jacobianMatchesNaiveOracleOnRandomScalars() {
        Random rnd = new Random(7);
        for (Curve c : new Curve[] {Curve.P256, Curve.SECP256K1}) {
            for (int i = 0; i < 12; i++) {
                BigInteger k = new BigInteger(1 + rnd.nextInt(256), rnd);
                assertEquals(naiveMul(c, k, c.g()), c.mul(k, c.g()), c.name() + " k=" + k.toString(16));
            }
        }
    }

    @Test
    void scalarMultiplicationIsHomomorphic() {
        Random rnd = new Random(11);
        for (Curve c : new Curve[] {Curve.P256, Curve.SECP256K1}) {
            BigInteger a = new BigInteger(255, rnd).add(BigInteger.ONE);
            BigInteger b = new BigInteger(255, rnd).add(BigInteger.ONE);
            Point pa = c.mul(a, c.g());
            Point pb = c.mul(b, c.g());
            assertEquals(c.mul(a.add(b), c.g()), c.add(pa, pb));
            assertEquals(c.mul(a.multiply(b).mod(c.n()), c.g()), c.mul(b, pa));
            assertEquals(c.add(pa, pb), c.add(pb, pa));
            assertEquals(c.mul(a.add(b), c.g()), c.mulAdd(a, c.g(), b, c.g()));
            assertEquals(c.add(c.mul(a, c.g()), c.mul(b, pb)), c.mulAdd(a, c.g(), b, pb));
        }
    }

    @Test
    void addEdgeCases() {
        Curve c = Curve.P256;
        Point g = c.g();
        Point negG = new Point(g.x(), c.p().subtract(g.y()));
        assertTrue(c.add(g, negG).isInfinity(), "P + (-P)");
        assertEquals(c.mul(BigInteger.TWO, g), c.add(g, g), "P + P doubles");
        assertEquals(g, c.add(g, Point.INFINITY));
        assertEquals(g, c.add(Point.INFINITY, g));
        assertTrue(c.add(Point.INFINITY, Point.INFINITY).isInfinity());
        // G + 2G + (-3G) = O exercises mixed Jacobian paths
        Point g2 = c.mul(BigInteger.TWO, g);
        Point g3 = c.add(g2, g);
        assertEquals(c.mul(BigInteger.valueOf(3), g), g3);
        assertTrue(c.add(c.add(g, g2), new Point(g3.x(), c.p().subtract(g3.y()))).isInfinity());
    }

    @Test
    void encodeDecodeRoundTrip() {
        Random rnd = new Random(5);
        for (Curve c : new Curve[] {Curve.P256, Curve.SECP256K1}) {
            for (int i = 0; i < 6; i++) {
                Point p = c.mul(new BigInteger(250, rnd).add(BigInteger.ONE), c.g());
                byte[] u = c.encode(p, false);
                byte[] z = c.encode(p, true);
                assertEquals(65, u.length);
                assertEquals(33, z.length);
                assertEquals(4, u[0]);
                assertEquals(p.y().testBit(0) ? 3 : 2, z[0]);
                assertEquals(p, c.decode(u));
                assertEquals(p, c.decode(z));
            }
        }
    }

    @Test
    void decodeRejectsMalformedPoints() {
        Curve c = Curve.P256;
        byte[] good = c.encode(c.g(), false);
        assertEquals(c.g(), c.decode(good), "control");
        assertThrows(IllegalArgumentException.class, () -> c.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> c.decode(new byte[] {0}), "infinity is not a key");
        byte[] badPrefix = good.clone();
        badPrefix[0] = 5;
        assertThrows(IllegalArgumentException.class, () -> c.decode(badPrefix));
        byte[] hybrid = good.clone();
        hybrid[0] = (byte) (6 + (c.g().y().testBit(0) ? 1 : 0));
        assertThrows(IllegalArgumentException.class, () -> c.decode(hybrid));
        assertThrows(IllegalArgumentException.class, () -> c.decode(java.util.Arrays.copyOf(good, 64)));
        assertThrows(IllegalArgumentException.class, () -> c.decode(java.util.Arrays.copyOf(good, 66)));
        byte[] offCurve = good.clone();
        offCurve[64] ^= 1; // y changes by one: no longer on curve
        assertThrows(IllegalArgumentException.class, () -> c.decode(offCurve));
    }

    @Test
    void decodeRejectsNonCanonicalCoordinates() {
        Curve c = Curve.SECP256K1; // p = 2^256 - 2^32 - 977, so small x + p still fits in 32 bytes
        BigInteger x = BigInteger.valueOf(1);
        // find a small x on the curve: rhs must be a QR; scan
        BigInteger px = null;
        BigInteger py = null;
        for (long t = 1; t < 200 && px == null; t++) {
            BigInteger xx = BigInteger.valueOf(t);
            BigInteger rhs = xx.pow(3).add(c.b()).mod(c.p());
            if (rhs.modPow(c.p().subtract(BigInteger.ONE).shiftRight(1), c.p()).equals(BigInteger.ONE)) {
                px = xx;
                py = rhs.modPow(c.p().add(BigInteger.ONE).shiftRight(2), c.p());
            }
        }
        assertNotNull(px);
        Point p = new Point(px, py);
        assertEquals(p, c.decode(c.encode(p, false)), "control");
        byte[] aliasX = new byte[65];
        aliasX[0] = 4;
        System.arraycopy(Curve.i2osp(px.add(c.p()), 32), 0, aliasX, 1, 32);
        System.arraycopy(Curve.i2osp(py, 32), 0, aliasX, 33, 32);
        assertThrows(IllegalArgumentException.class, () -> c.decode(aliasX), "x >= p");
        byte[] comp = c.encode(p, true);
        System.arraycopy(Curve.i2osp(px.add(c.p()), 32), 0, comp, 1, 32);
        assertThrows(IllegalArgumentException.class, () -> c.decode(comp), "compressed x >= p");
    }

    @Test
    void compressedXWithoutSquareRootIsRejected() {
        Curve c = Curve.P256;
        BigInteger half = c.p().subtract(BigInteger.ONE).shiftRight(1);
        int rejected = 0;
        int accepted = 0;
        for (long t = 1; t <= 30; t++) {
            BigInteger x = BigInteger.valueOf(t);
            BigInteger rhs = x.pow(3).add(c.a().multiply(x)).add(c.b()).mod(c.p());
            boolean residue = rhs.modPow(half, c.p()).equals(BigInteger.ONE);
            byte[] enc = new byte[33];
            enc[0] = 2;
            System.arraycopy(Curve.i2osp(x, 32), 0, enc, 1, 32);
            if (residue) {
                Point p = c.decode(enc);
                assertTrue(c.onCurve(p.x(), p.y()));
                assertFalse(p.y().testBit(0), "prefix 02 means even y");
                accepted++;
            } else {
                assertThrows(IllegalArgumentException.class, () -> c.decode(enc), "x=" + x);
                rejected++;
            }
        }
        assertTrue(rejected >= 5 && accepted >= 5, "mix " + rejected + "/" + accepted);
    }
}
