package ae.rsync;

import java.util.Arrays;
import java.util.Objects;

/** Receiver side: rebuilds the target from the basis and a delta, verifying the result. */
public final class Patcher {
    private Patcher() {}

    public static byte[] apply(byte[] basis, Delta delta) throws PatchException {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(delta, "delta");
        if (delta.targetLength() > Integer.MAX_VALUE - 8) {
            throw new PatchException("target too large: " + delta.targetLength());
        }
        byte[] out = new byte[(int) delta.targetLength()];
        int pos = 0;
        for (Delta.Op op : delta.ops()) {
            if (op instanceof Delta.Copy c) {
                if (c.offset() > basis.length || c.length() > basis.length - c.offset()) {
                    throw new PatchException("copy range " + c.offset() + "+" + c.length()
                            + " outside basis of " + basis.length + " bytes");
                }
                System.arraycopy(basis, (int) c.offset(), out, pos, c.length());
                pos += c.length();
            } else {
                byte[] data = ((Delta.Literal) op).data();
                System.arraycopy(data, 0, out, pos, data.length);
                pos += data.length;
            }
        }
        if (!Arrays.equals(Delta.sha256(out), delta.targetSha256())) {
            throw new PatchException("digest mismatch: basis is not the one the delta was made against");
        }
        return out;
    }
}
