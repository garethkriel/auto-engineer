package bench.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Allocator {
    private Allocator() {}

    public static List<BigDecimal> allocate(BigDecimal amount, int[] weights, int scale) {
        if (amount == null || weights == null || weights.length == 0 || scale < 0) {
            throw new IllegalArgumentException("invalid argument");
        }
        BigInteger total = BigInteger.ZERO;
        for (int w : weights) {
            if (w < 0) throw new IllegalArgumentException("negative weight");
            total = total.add(BigInteger.valueOf(w));
        }
        if (total.signum() == 0) throw new IllegalArgumentException("all weights zero");
        BigDecimal stripped = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        if (stripped.scale() > scale) throw new IllegalArgumentException("too many decimal places");
        BigInteger units = amount.movePointRight(scale).toBigIntegerExact();
        BigInteger absUnits = units.abs();
        int sign = units.signum();
        int n = weights.length;
        BigInteger[] part = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = absUnits.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(total);
            part[i] = qr[0];
            rem[i] = qr[1];
            assigned = assigned.add(qr[0]);
        }
        int missing = absUnits.subtract(assigned).intValueExact();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> {
            int c = rem[y].compareTo(rem[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        for (int k = 0; k < missing; k++) part[idx[k]] = part[idx[k]].add(BigInteger.ONE);
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigInteger v = sign < 0 ? part[i].negate() : part[i];
            out.add(new BigDecimal(v, scale));
        }
        return out;
    }
}
