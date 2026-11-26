package ae.pk;

import java.math.BigInteger;

/** An affine curve point; {@link #INFINITY} (null coordinates) is the group identity. */
public record Point(BigInteger x, BigInteger y) {
    /** The point at infinity. */
    public static final Point INFINITY = new Point(null, null);

    /** True for the group identity. */
    public boolean isInfinity() {
        return x == null;
    }
}
