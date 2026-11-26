package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A tiny curve found by brute force makes nonce-retry and degenerate-signature paths reachable. */
class ToyCurveTest {
    static final int P = 103; // 103 = 3 mod 4
    static final Curve TOY = findToyCurve();

    private static Curve findToyCurve() {
        for (int a = 1; a < P; a++) {
            for (int b = 1; b < P; b++) {
                if ((4 * a * a * a + 27 * b * b) % P == 0) continue; // singular
                if (BigInteger.valueOf(b).modPow(BigInteger.valueOf((P - 1) / 2), BigInteger.valueOf(P)).intValue() != 1) continue; // want a point with x = 0
                List<int[]> pts = new ArrayList<>();
                for (int x = 0; x < P; x++) {
                    for (int y = 0; y < P; y++) {
                        if ((y * y - (x * x * x + a * x + b)) % P == 0) pts.add(new int[] {x, y});
                    }
                }
                int order = pts.size() + 1; // plus infinity
                if (BigInteger.valueOf(order).isProbablePrime(30) && order > 60) {
                    int[] g = pts.get(0);
                    return new Curve("toy", BigInteger.valueOf(P), BigInteger.valueOf(a), BigInteger.valueOf(b),
                        BigInteger.valueOf(order), BigInteger.valueOf(g[0]), BigInteger.valueOf(g[1]));
                }
            }
        }
        throw new AssertionError("no toy curve");
    }

    @Test
    void curveConstructorValidates() {
        assertTrue(TOY.n().intValue() > 60);
        assertThrows(IllegalArgumentException.class, () -> new Curve("bad", TOY.p(), TOY.a(), TOY.b(),
            TOY.n().add(BigInteger.ONE), TOY.g().x(), TOY.g().y()), "wrong order");
        assertThrows(IllegalArgumentException.class, () -> new Curve("bad", TOY.p(), TOY.a(), TOY.b(),
            TOY.n(), TOY.g().x(), TOY.g().y().add(BigInteger.ONE)), "G off curve");
        assertThrows(IllegalArgumentException.class, () -> new Curve("bad", BigInteger.valueOf(101), TOY.a(), TOY.b(),
            TOY.n(), TOY.g().x(), TOY.g().y()), "p = 1 mod 4");
    }

    @Test
    void everyScalarMatchesNaiveOracleAndEncodingRoundTrips() {
        Point acc = Point.INFINITY;
        for (int k = 0; k <= TOY.n().intValue(); k++) {
            Point m = TOY.mul(BigInteger.valueOf(k), TOY.g());
            assertEquals(acc, m, "k=" + k);
            assertEquals(CurveTest.naiveMul(TOY, BigInteger.valueOf(k), TOY.g()), m);
            if (!m.isInfinity()) {
                assertEquals(m, TOY.decode(TOY.encode(m, true)), "compressed k=" + k);
                assertEquals(m, TOY.decode(TOY.encode(m, false)), "uncompressed k=" + k);
            }
            acc = CurveTest.naiveAdd(TOY, acc, TOY.g());
        }
        assertEquals(TOY.g(), acc, "after n+1 additions the naive accumulator is (n+1)G = G");
    }

    @Test
    void signVerifyForEveryKeyAndManyHashesIncludingRetries() throws Exception {
        int n = TOY.n().intValue();
        int rejectedNonces = 0;
        int signatures = 0;
        int wrongKeyAccepted = 0;
        for (int d = 1; d < n; d++) {
            Point q = Ecdsa.publicKey(TOY, BigInteger.valueOf(d));
            for (int i = 0; i < 12; i++) {
                byte[] hash = EcdsaTest.sha("k" + d + "/" + i);
                Ecdsa.Signature sig = Ecdsa.sign(TOY, BigInteger.valueOf(d), hash);
                assertTrue(Ecdsa.verify(TOY, q, hash, sig), "d=" + d + " i=" + i);
                BigInteger k = Ecdsa.nonce(TOY, BigInteger.valueOf(d), hash);
                assertTrue(k.signum() > 0 && k.compareTo(TOY.n()) < 0);
                signatures++;
                // the nonce the signature actually used differs from the first candidate when a retry happened
                BigInteger r1 = TOY.mul(k, TOY.g()).x().mod(TOY.n());
                if (!r1.equals(sig.r())) rejectedNonces++;
                int other = d % (n - 1) + 1; // a different key; on a ~100-element group ~1/n of wrong keys still verify
                if (Ecdsa.verify(TOY, Ecdsa.publicKey(TOY, BigInteger.valueOf(other)), hash, sig)) wrongKeyAccepted++;
            }
        }
        assertTrue(wrongKeyAccepted < signatures / 10, "wrong-key accepts " + wrongKeyAccepted + "/" + signatures);
        assertTrue(rejectedNonces > 0, "retry path exercised (" + rejectedNonces + "/" + signatures + ")");
    }

    @Test
    void nonCanonicalCoordinatesAreRejectedAtTheBoundary() {
        // fieldBytes = 1, so every byte value 0..255 is encodable; only 0..102 are canonical
        assertEquals(1, TOY.fieldBytes());
        BigInteger half = BigInteger.valueOf((P - 1) / 2);
        int canonicalAccepted = 0;
        for (int x = 0; x < 256; x++) {
            BigInteger rhs = BigInteger.valueOf(x).pow(3).add(TOY.a().multiply(BigInteger.valueOf(x))).add(TOY.b()).mod(TOY.p());
            boolean residue = rhs.signum() == 0 || rhs.modPow(half, TOY.p()).equals(BigInteger.ONE);
            for (int prefix = 2; prefix <= 3; prefix++) {
                byte[] enc = {(byte) prefix, (byte) x};
                if (x < P && residue && !(rhs.signum() == 0 && prefix == 3)) {
                    assertEquals(prefix == 3, TOY.decode(enc).y().testBit(0), "x=" + x);
                    canonicalAccepted++;
                } else {
                    final int fx = x;
                    assertThrows(IllegalArgumentException.class, () -> TOY.decode(enc), "x=" + fx + " prefix=" + prefix);
                }
            }
        }
        assertTrue(canonicalAccepted > 100, "accepted " + canonicalAccepted);
    }

    @Test
    void uncompressedCoordinatesAtLeastPAreRejected() {
        int checkedAliases = 0;
        for (int k = 1; k < TOY.n().intValue(); k++) {
            Point m = TOY.mul(BigInteger.valueOf(k), TOY.g());
            int x = m.x().intValue();
            int y = m.y().intValue();
            assertEquals(m, TOY.decode(new byte[] {4, (byte) x, (byte) y}), "control k=" + k);
            if (y + P < 256) {
                assertThrows(IllegalArgumentException.class, () -> TOY.decode(new byte[] {4, (byte) x, (byte) (y + P)}), "y alias");
                checkedAliases++;
            }
            if (x + P < 256) {
                assertThrows(IllegalArgumentException.class, () -> TOY.decode(new byte[] {4, (byte) (x + P), (byte) y}), "x alias");
                checkedAliases++;
            }
        }
        assertTrue(checkedAliases > 20, "aliases checked: " + checkedAliases);
    }
}
