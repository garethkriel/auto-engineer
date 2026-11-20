package bench.orders;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Aggregate statistics over a snapshot of orders. */
public final class OrderStats {
    private final List<Order> orders;

    public OrderStats(List<Order> orders) {
        if (orders == null) {
            throw new IllegalArgumentException("orders is null");
        }
        for (Order o : orders) {
            if (o == null) {
                throw new IllegalArgumentException("null order");
            }
        }
        this.orders = new ArrayList<>(orders);
    }

    public Map<String, BigDecimal> totalByCustomer() {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Order o : orders) {
            m.merge(o.customer(), o.amount(), BigDecimal::add);
        }
        return m;
    }

    public List<String> topCustomers(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n < 0");
        }
        return totalByCustomer().entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder()))))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    public SortedMap<YearMonth, BigDecimal> monthlyTotals() {
        SortedMap<YearMonth, BigDecimal> m = new TreeMap<>();
        for (Order o : orders) {
            m.merge(YearMonth.from(o.date()), o.amount(), BigDecimal::add);
        }
        return m;
    }
}
