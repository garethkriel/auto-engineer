package ae.layout;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.IntUnaryOperator;

/** Brute-force reference: enumerates every subset of legal breaks. Independent of Breaker. */
final class Oracle {
    private Oracle() {}

    static List<Integer> legalBreaks(List<Item> items) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof Item.Glue && i > 0 && items.get(i - 1) instanceof Item.Box) {
                out.add(i);
            } else if (it instanceof Item.Penalty p && p.penalty() < Item.PENALTY_INF) {
                out.add(i);
            }
        }
        return out;
    }

    static boolean forced(Item it) {
        return it instanceof Item.Penalty p && p.penalty() <= -Item.PENALTY_INF;
    }

    /** Total demerits of the given break list, or empty if any line is infeasible. */
    static OptionalLong cost(List<Item> items, IntUnaryOperator width, Params params, List<Integer> breaks) {
        long total = 0;
        int prev = -1;
        boolean prevFlagged = false;
        int line = 0;
        for (int b : breaks) {
            int start = prev + 1;
            while (start < b && !(items.get(start) instanceof Item.Box)) {
                start++;
            }
            long w = 0;
            long y = 0;
            long z = 0;
            for (int k = start; k < b; k++) {
                Item it = items.get(k);
                if (it instanceof Item.Box bx) {
                    w += bx.width();
                } else if (it instanceof Item.Glue g) {
                    w += g.width();
                    y += g.stretch();
                    z += g.shrink();
                }
            }
            Item br = items.get(b);
            int pen = 0;
            boolean flagged = false;
            if (br instanceof Item.Penalty p) {
                w += p.width();
                pen = p.penalty();
                flagged = p.flagged();
            }
            long d = width.applyAsInt(line) - w;
            BigInteger bad;
            if (d == 0) {
                bad = BigInteger.ZERO;
            } else {
                long denom = d > 0 ? y : z;
                if (denom <= 0 || (d < 0 && -d > z)) {
                    return OptionalLong.empty();
                }
                BigInteger ad = BigInteger.valueOf(Math.abs(d));
                bad = ad.pow(3).multiply(BigInteger.valueOf(100))
                        .divide(BigInteger.valueOf(denom).pow(3));
            }
            if (bad.compareTo(BigInteger.valueOf(params.tolerance())) > 0) {
                return OptionalLong.empty();
            }
            BigInteger dem = BigInteger.valueOf(params.linePenalty()).add(bad).pow(2);
            if (pen >= 0) {
                dem = dem.add(BigInteger.valueOf((long) pen * pen));
            } else if (pen > -Item.PENALTY_INF) {
                dem = dem.subtract(BigInteger.valueOf((long) pen * pen));
            }
            if (flagged && prevFlagged) {
                dem = dem.add(BigInteger.valueOf(params.flaggedDemerits()));
            }
            total += dem.longValueExact();
            prev = b;
            prevFlagged = flagged;
            line++;
        }
        return OptionalLong.of(total);
    }

    /** Minimum demerits over all subsets that contain every forced break and the last item. */
    static OptionalLong best(List<Item> items, IntUnaryOperator width, Params params) {
        List<Integer> legal = legalBreaks(items);
        int m = legal.size();
        long bestCost = Long.MAX_VALUE;
        boolean found = false;
        for (long mask = 0; mask < (1L << m); mask++) {
            List<Integer> chosen = new ArrayList<>();
            boolean ok = true;
            for (int k = 0; k < m; k++) {
                boolean in = (mask & (1L << k)) != 0;
                if (in) {
                    chosen.add(legal.get(k));
                } else if (forced(items.get(legal.get(k)))) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            OptionalLong c = cost(items, width, params, chosen);
            if (c.isPresent() && (!found || c.getAsLong() < bestCost)) {
                bestCost = c.getAsLong();
                found = true;
            }
        }
        return found ? OptionalLong.of(bestCost) : OptionalLong.empty();
    }
}
