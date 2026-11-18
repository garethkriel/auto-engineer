package bench.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Largest-remainder proportional allocation of money. */
public final class Allocator {
    private Allocator() {
    }

    public static List<BigDecimal> allocate(BigDecimal amount, int[] weights, int scale) {
        if (amount == null || weights == null || weights.length == 0 || scale < 0) {
            throw new IllegalArgumentException("invalid arguments");
        }
        long total = 0;
        for (int w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("negative weight");
            }
            total += w;
        }
        if (total == 0) {
            throw new IllegalArgumentException("all weights zero");
        }
        if (amount.signum() != 0 && amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("amount has too many decimals");
        }
        BigInteger units = amount.setScale(scale).unscaledValue();
        BigInteger bigTotal = BigInteger.valueOf(total);
        int n = weights.length;
        BigInteger[] part = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = units.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(bigTotal);
            part[i] = qr[0];
            rem[i] = qr[1].abs();
            assigned = assigned.add(qr[0]);
        }
        int missing = units.subtract(assigned).abs().intValueExact();
        BigInteger step = BigInteger.valueOf(units.signum());
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (weights[i] > 0) {
                idx.add(i);
            }
        }
        idx.sort((p, q) -> {
            int c = rem[q].compareTo(rem[p]);
            return c != 0 ? c : Integer.compare(p, q);
        });
        for (int k = 0; k < missing; k++) {
            int i = idx.get(k);
            part[i] = part[i].add(step);
        }
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BigDecimal(part[i], scale));
        }
        return out;
    }
}
