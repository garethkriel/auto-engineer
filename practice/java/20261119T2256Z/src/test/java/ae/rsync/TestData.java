package ae.rsync;

import java.util.Random;

/** Shared deterministic helpers for the tests. */
final class TestData {
    private TestData() {}

    static byte[] random(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static byte[] slice(byte[] d, int from, int to) {
        return java.util.Arrays.copyOfRange(d, from, to);
    }

    static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Literal bytes carried by a delta. */
    static long literalBytes(Delta d) {
        long n = 0;
        for (Delta.Op op : d.ops()) {
            if (op instanceof Delta.Literal l) {
                n += l.data().length;
            }
        }
        return n;
    }
}
