package ae;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Stream-based analytics over orders. All results are unmodifiable. */
public final class Analytics {
    private Analytics() {}

    /** Custom collector; the mutable accumulator is a one-element array holding an immutable Stats. */
    public static Collector<Order, ?, Stats> statsCollector() {
        return Collector.of(
                () -> new Stats[] {Stats.EMPTY},
                (acc, o) -> acc[0] = acc[0].accept(o.amount()),
                (a, b) -> {
                    a[0] = a[0].combine(b[0]);
                    return a;
                },
                acc -> acc[0]);
    }

    public static Map<String, Stats> statsByCustomer(List<Order> orders) {
        return Collections.unmodifiableMap(
                orders.stream().collect(Collectors.groupingBy(Order::customer, TreeMap::new, statsCollector())));
    }

    public static Optional<String> topCategory(List<Order> orders, String customer) {
        return orders.stream()
                .filter(o -> o.customer().equals(customer))
                .collect(Collectors.groupingBy(Order::category, TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Order::amount, BigDecimal::add)))
                .entrySet().stream()
                // TreeMap order + stable max-by-first: keep earlier key on equal totals
                .reduce((a, b) -> b.getValue().compareTo(a.getValue()) > 0 ? b : a)
                .map(Map.Entry::getKey);
    }

    public static Map<Boolean, List<Order>> splitAtThreshold(List<Order> orders, BigDecimal threshold) {
        Map<Boolean, List<Order>> m = orders.stream()
                .collect(Collectors.partitioningBy(o -> o.amount().compareTo(threshold) >= 0));
        return Map.of(true, List.copyOf(m.get(true)), false, List.copyOf(m.get(false)));
    }

    public static Map<YearMonth, BigDecimal> monthlyTotals(List<Order> orders) {
        return Collections.unmodifiableMap(orders.stream().collect(Collectors.groupingBy(
                o -> YearMonth.from(o.date()), TreeMap::new,
                Collectors.reducing(BigDecimal.ZERO, Order::amount, BigDecimal::add))));
    }
}
