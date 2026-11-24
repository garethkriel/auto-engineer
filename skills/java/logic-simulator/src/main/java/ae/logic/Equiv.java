package ae.logic;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Exhaustive combinational equivalence checking over packed 64-vector words. */
public final class Equiv {
    /** Inputs above this make the 2^n sweep too slow for a library call. */
    public static final int MAX_INPUTS = 20;

    private static final long[] LOW = {
        0xAAAAAAAAAAAAAAAAL, 0xCCCCCCCCCCCCCCCCL, 0xF0F0F0F0F0F0F0F0L,
        0xFF00FF00FF00FF00L, 0xFFFF0000FFFF0000L, 0xFFFFFFFF00000000L};

    private Equiv() {}

    /**
     * Compares {@code a} and {@code b} by input and output NAME. Returns the failing input vector with the
     * smallest index (first input of {@code a} is the least significant bit), or empty if equivalent.
     */
    public static Optional<Map<String, Boolean>> check(Netlist a, Netlist b) {
        if (!a.isCombinational() || !b.isCombinational()) throw new IllegalArgumentException("DFFs are not supported");
        if (!new HashSet<>(a.inputs()).equals(new HashSet<>(b.inputs())))
            throw new IllegalArgumentException("different input sets");
        if (!new HashSet<>(a.outputs()).equals(new HashSet<>(b.outputs())))
            throw new IllegalArgumentException("different output sets");
        int n = a.inputs().size();
        if (n > MAX_INPUTS) throw new IllegalArgumentException("more than " + MAX_INPUTS + " inputs");
        int[] bIndexOfA = new int[n];
        for (int i = 0; i < n; i++) bIndexOfA[i] = b.inputs().indexOf(a.inputs().get(i));
        int[] bOut = new int[a.outputs().size()];
        for (int o = 0; o < bOut.length; o++) bOut[o] = b.outputs().indexOf(a.outputs().get(o));

        long total = 1L << n;
        long mask = n >= 6 ? -1L : (1L << (1 << n)) - 1;
        long[] wa = new long[n], wb = new long[n];
        for (long base = 0; base < total; base += 64) {
            for (int i = 0; i < n; i++) {
                wa[i] = i < 6 ? LOW[i] : (((base >> i) & 1) == 1 ? -1L : 0L);
                wb[bIndexOfA[i]] = wa[i];
            }
            long[] oa = a.evalPacked(wa);
            long[] ob = b.evalPacked(wb);
            long diff = 0;
            for (int o = 0; o < oa.length; o++) diff |= oa[o] ^ ob[bOut[o]];
            diff &= mask;
            if (diff != 0) {
                long v = base + Long.numberOfTrailingZeros(diff);
                Map<String, Boolean> cex = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) cex.put(a.inputs().get(i), ((v >> i) & 1) == 1);
                return Optional.of(cex);
            }
        }
        return Optional.empty();
    }
}
