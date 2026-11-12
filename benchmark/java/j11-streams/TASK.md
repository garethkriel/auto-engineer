# j11-streams: Order statistics

Java 21, standard library only. Put your solution under `bench-solutions/java/j11-streams/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.orders;
public record Order(String customer, BigDecimal amount, LocalDate date) {}
public final class OrderStats {
    public OrderStats(List<Order> orders);
    public Map<String, BigDecimal> totalByCustomer();
    public List<String> topCustomers(int n);
    public SortedMap<YearMonth, BigDecimal> monthlyTotals();
}
```

- The constructor keeps its own copy of the orders, so later changes to the caller's list don't affect results. A null list throws `IllegalArgumentException`.
- `totalByCustomer` maps each customer to the sum of their amounts, using exact `BigDecimal` arithmetic.
- `topCustomers(n)` returns up to `n` customer names by total descending, with ties broken by name ascending. `n < 0` throws `IllegalArgumentException`.
- `monthlyTotals` maps the `YearMonth` of each order's date to the sum of amounts, in chronological order.
