package bench.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Largest-remainder proportional allocation of a money amount. */
public final class Allocator {
    private Allocator() {
    }

    public static List<BigDecimal> allocate(BigDecimal amount, int[] weights, int scale) {
        if (amount == null || weights == null || weights.length == 0 || scale < 0) {
            throw new IllegalArgumentException("invalid arguments");
        }
        BigInteger sum = BigInteger.ZERO;
        for (int w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("negative weight");
            }
            sum = sum.add(BigInteger.valueOf(w));
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("all weights zero");
        }
        if (amount.signum() != 0 && amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("too many decimal places");
        }
        BigInteger units = amount.setScale(scale).unscaledValue();
        int sign = units.signum();
        BigInteger abs = units.abs();
        int n = weights.length;
        BigInteger[] part = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = abs.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
            part[i] = qr[0];
            rem[i] = qr[1];
            given = given.add(qr[0]);
        }
        int missing = abs.subtract(given).intValueExact();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (p, q) -> {
            int c = rem[q].compareTo(rem[p]);
            return c != 0 ? c : Integer.compare(p, q);
        });
        for (int k = 0; k < missing; k++) {
            part[order[k]] = part[order[k]].add(BigInteger.ONE);
        }
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigInteger v = sign < 0 ? part[i].negate() : part[i];
            out.add(new BigDecimal(v, scale));
        }
        return out;
    }
}
