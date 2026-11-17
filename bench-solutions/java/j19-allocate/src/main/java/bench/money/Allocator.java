package bench.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Largest-remainder proportional allocation of money. */
public final class Allocator {
    private Allocator() {}

    public static List<BigDecimal> allocate(BigDecimal amount, int[] weights, int scale) {
        if (amount == null || weights == null || weights.length == 0 || scale < 0) {
            throw new IllegalArgumentException("bad arguments");
        }
        long sum = 0;
        for (int w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("negative weight");
            }
            sum += w;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("all weights zero");
        }
        if (amount.signum() != 0 && amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("amount has too many decimals");
        }
        BigInteger units = amount.movePointRight(scale).toBigIntegerExact();
        int sign = units.signum();
        BigInteger abs = units.abs();
        BigInteger total = BigInteger.valueOf(sum);
        int n = weights.length;
        BigInteger[] share = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = abs.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(total);
            share[i] = qr[0];
            rem[i] = qr[1];
            assigned = assigned.add(qr[0]);
        }
        long missing = abs.subtract(assigned).longValueExact();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (p, q) -> {
            int c = rem[q].compareTo(rem[p]);
            return c != 0 ? c : Integer.compare(p, q);
        });
        for (int k = 0; k < missing; k++) {
            share[idx[k]] = share[idx[k]].add(BigInteger.ONE);
        }
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigInteger v = sign < 0 ? share[i].negate() : share[i];
            out.add(new BigDecimal(v, scale));
        }
        return out;
    }
}
