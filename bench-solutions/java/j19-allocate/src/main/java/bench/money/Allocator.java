package bench.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Largest-remainder proportional allocation of a decimal amount. */
public final class Allocator {
    private Allocator() {
    }

    public static List<BigDecimal> allocate(BigDecimal amount, int[] weights, int scale) {
        if (amount == null || weights == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (weights.length == 0) {
            throw new IllegalArgumentException("empty weights");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("negative scale");
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
        int n = weights.length;
        BigInteger[] part = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger num = units.multiply(BigInteger.valueOf(weights[i]));
            BigInteger[] qr = num.divideAndRemainder(sum);
            part[i] = qr[0];
            rem[i] = qr[1].abs();
            assigned = assigned.add(qr[0]);
        }
        long missing = units.subtract(assigned).abs().longValueExact();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (p, q) -> {
            int c = rem[q].compareTo(rem[p]);
            return c != 0 ? c : Integer.compare(p, q);
        });
        for (int k = 0; k < missing; k++) {
            int idx = order[k];
            part[idx] = part[idx].add(BigInteger.valueOf(sign));
        }
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BigDecimal(part[i], scale));
        }
        return out;
    }
}
