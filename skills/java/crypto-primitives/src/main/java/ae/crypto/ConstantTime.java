package ae.crypto;

import java.util.Objects;

/** Comparison helpers whose running time does not depend on where the inputs differ. */
public final class ConstantTime {
    private ConstantTime() {}

    /** Returns true when both arrays have equal length and content; scans the whole shorter-or-equal length. */
    public static boolean equals(byte[] a, byte[] b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int diff = a.length ^ b.length;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
