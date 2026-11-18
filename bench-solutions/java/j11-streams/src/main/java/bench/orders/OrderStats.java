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

/** Aggregate statistics over an immutable snapshot of orders. */
public final class OrderStats {
    private final List<Order> orders;

    public OrderStats(List<Order> orders) {
        if (orders == null) {
            throw new IllegalArgumentException("orders must not be null");
        }
        List<Order> copy = new ArrayList<>(orders);
        for (Order o : copy) {
            if (o == null) {
                throw new IllegalArgumentException("null order");
            }
        }
        this.orders = List.copyOf(copy);
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
            throw new IllegalArgumentException("n must be >= 0");
        }
        Map<String, BigDecimal> totals = totalByCustomer();
        List<Map.Entry<String, BigDecimal>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < n; i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }

    public SortedMap<YearMonth, BigDecimal> monthlyTotals() {
        SortedMap<YearMonth, BigDecimal> m = new TreeMap<>();
        for (Order o : orders) {
            m.merge(YearMonth.from(o.date()), o.amount(), BigDecimal::add);
        }
        return m;
    }
}
