package ae.pk;

/** Elliptic-curve Diffie-Hellman producing the raw x coordinate (as in the JDK's "ECDH"). */
public final class Ecdh {
    private Ecdh() { }

    /** Shared secret x(d * peer), fixed width. Validates d and the peer point (invalid-curve defence). */
    public static byte[] sharedSecret(Curve c, java.math.BigInteger d, Point peer) {
        Ecdsa.checkScalar(c, d);
        if (peer.isInfinity() || !c.onCurve(peer.x(), peer.y())) {
            throw new IllegalArgumentException("invalid peer public key");
        }
        Point shared = c.mul(d, peer);
        if (shared.isInfinity()) {
            throw new IllegalArgumentException("degenerate shared point");
        }
        return Curve.i2osp(shared.x(), c.fieldBytes());
    }
}
