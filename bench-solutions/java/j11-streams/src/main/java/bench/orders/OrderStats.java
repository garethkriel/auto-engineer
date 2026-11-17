package bench.orders;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Aggregations over a snapshot of orders. */
public final class OrderStats {
    private final List<Order> orders;

    public OrderStats(List<Order> orders) {
        if (orders == null) {
            throw new IllegalArgumentException("null orders");
        }
        for (Order o : orders) {
            if (o == null || o.customer() == null || o.amount() == null || o.date() == null) {
                throw new IllegalArgumentException("invalid order");
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
        Map<String, BigDecimal> totals = totalByCustomer();
        List<String> names = new ArrayList<>(totals.keySet());
        names.sort((a, b) -> {
            int c = totals.get(b).compareTo(totals.get(a));
            return c != 0 ? c : a.compareTo(b);
        });
        return Collections.unmodifiableList(new ArrayList<>(names.subList(0, Math.min(n, names.size()))));
    }

    public SortedMap<YearMonth, BigDecimal> monthlyTotals() {
        SortedMap<YearMonth, BigDecimal> m = new TreeMap<>();
        for (Order o : orders) {
            m.merge(YearMonth.from(o.date()), o.amount(), BigDecimal::add);
        }
        return m;
    }
}
