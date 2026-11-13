package bench.orders;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Aggregate statistics over orders. */
public final class OrderStats {
    private final List<Order> orders;

    public OrderStats(List<Order> orders) {
        if (orders == null) throw new IllegalArgumentException("null orders");
        this.orders = List.copyOf(orders);
    }

    public Map<String, BigDecimal> totalByCustomer() {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Order o : orders) m.merge(o.customer(), o.amount(), BigDecimal::add);
        return m;
    }

    public List<String> topCustomers(int n) {
        if (n < 0) throw new IllegalArgumentException("n < 0");
        List<Map.Entry<String, BigDecimal>> es = new ArrayList<>(totalByCustomer().entrySet());
        es.sort((a, b) -> {
            int c = b.getValue().compareTo(a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(es.get(i).getKey());
        return out;
    }

    public SortedMap<YearMonth, BigDecimal> monthlyTotals() {
        SortedMap<YearMonth, BigDecimal> m = new TreeMap<>();
        for (Order o : orders) m.merge(YearMonth.from(o.date()), o.amount(), BigDecimal::add);
        return m;
    }
}
