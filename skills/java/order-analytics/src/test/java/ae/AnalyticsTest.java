package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AnalyticsTest {
    private static Order o(String c, String cat, String amt, String date) {
        return new Order(c, cat, new BigDecimal(amt), LocalDate.parse(date));
    }

    private final List<Order> orders = List.of(
            o("ann", "books", "10.00", "2026-01-05"),
            o("ann", "games", "30.00", "2026-01-20"),
            o("bob", "books", "5.50", "2026-02-01"),
            o("ann", "games", "20.00", "2026-02-10"),
            o("bob", "food", "5.50", "2026-02-11"));

    @Test
    void orderRejectsNullAndNegative() {
        assertThrows(NullPointerException.class, () -> new Order(null, "a", BigDecimal.ONE, LocalDate.now()));
        assertThrows(NullPointerException.class, () -> new Order("a", "a", null, LocalDate.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Order("a", "a", new BigDecimal("-0.01"), LocalDate.now()));
    }

    @Test
    void emptyStatsHasNoMean() {
        assertEquals(0, Stats.EMPTY.count());
        assertEquals(Optional.empty(), Stats.EMPTY.mean());
    }

    @Test
    void acceptTracksMinMaxSum() {
        Stats s = Stats.EMPTY.accept(new BigDecimal("3")).accept(new BigDecimal("1")).accept(new BigDecimal("2"));
        assertEquals(3, s.count());
        assertEquals(new BigDecimal("6"), s.sum());
        assertEquals(new BigDecimal("1"), s.min());
        assertEquals(new BigDecimal("3"), s.max());
        assertEquals(Optional.of(new BigDecimal("2.00")), s.mean());
    }

    @Test
    void meanRoundsHalfUp() {
        Stats s = Stats.EMPTY.accept(new BigDecimal("1")).accept(new BigDecimal("2")).accept(new BigDecimal("2"));
        assertEquals(Optional.of(new BigDecimal("1.67")), s.mean());
        Stats t = Stats.EMPTY.accept(new BigDecimal("0.01")).accept(new BigDecimal("0.00"));
        assertEquals(Optional.of(new BigDecimal("0.01")), t.mean());
    }

    @Test
    void combineWithEmptyIsIdentity() {
        Stats s = Stats.EMPTY.accept(new BigDecimal("4"));
        assertEquals(s, s.combine(Stats.EMPTY));
        assertEquals(s, Stats.EMPTY.combine(s));
    }

    @Test
    void combineMergesExtremes() {
        Stats a = Stats.EMPTY.accept(new BigDecimal("4")).accept(new BigDecimal("9"));
        Stats b = Stats.EMPTY.accept(new BigDecimal("1")).accept(new BigDecimal("5"));
        Stats c = a.combine(b);
        assertEquals(4, c.count());
        assertEquals(new BigDecimal("19"), c.sum());
        assertEquals(new BigDecimal("1"), c.min());
        assertEquals(new BigDecimal("9"), c.max());
    }

    @Test
    void collectorMatchesSequentialAndParallel() {
        List<Order> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add(o("c", "k", String.valueOf(i % 37), "2026-01-01"));
        }
        Stats seq = many.stream().collect(Analytics.statsCollector());
        Stats par = many.parallelStream().collect(Analytics.statsCollector());
        assertEquals(1000, seq.count());
        assertEquals(seq, par);
        assertEquals(new BigDecimal("0"), seq.min());
        assertEquals(new BigDecimal("36"), seq.max());
    }

    @Test
    void collectorOnEmptyStreamIsEmpty() {
        assertEquals(Stats.EMPTY, List.<Order>of().stream().collect(Analytics.statsCollector()));
    }

    @Test
    void statsByCustomerIsSortedAndCorrect() {
        Map<String, Stats> m = Analytics.statsByCustomer(orders);
        assertEquals(List.of("ann", "bob"), List.copyOf(m.keySet()));
        assertEquals(3, m.get("ann").count());
        assertEquals(new BigDecimal("60.00"), m.get("ann").sum());
        assertEquals(new BigDecimal("5.50"), m.get("bob").min());
        assertEquals(new BigDecimal("11.00"), m.get("bob").sum());
    }

    @Test
    void statsByCustomerOfEmptyIsEmptyMap() {
        assertTrue(Analytics.statsByCustomer(List.of()).isEmpty());
    }

    @Test
    void topCategoryPicksHighestTotal() {
        assertEquals(Optional.of("games"), Analytics.topCategory(orders, "ann"));
    }

    @Test
    void topCategoryTieBreaksAlphabetically() {
        assertEquals(Optional.of("books"), Analytics.topCategory(orders, "bob"));
    }

    @Test
    void topCategoryUnknownCustomerIsEmpty() {
        assertEquals(Optional.empty(), Analytics.topCategory(orders, "zed"));
    }

    @Test
    void splitAtThresholdIsInclusive() {
        Map<Boolean, List<Order>> m = Analytics.splitAtThreshold(orders, new BigDecimal("10.00"));
        assertEquals(3, m.get(true).size());
        assertEquals(List.of("bob", "bob"), m.get(false).stream().map(Order::customer).toList());
        assertTrue(m.get(true).contains(orders.get(0)));
    }

    @Test
    void splitKeepsBothKeysWhenOneSideEmpty() {
        Map<Boolean, List<Order>> m = Analytics.splitAtThreshold(orders, new BigDecimal("1000"));
        assertTrue(m.get(true).isEmpty());
        assertEquals(5, m.get(false).size());
    }

    @Test
    void splitIgnoresScaleDifferences() {
        Map<Boolean, List<Order>> m = Analytics.splitAtThreshold(orders, new BigDecimal("5.5"));
        assertEquals(5, m.get(true).size());
    }

    @Test
    void monthlyTotalsSortedByMonth() {
        Map<YearMonth, BigDecimal> m = Analytics.monthlyTotals(orders);
        assertEquals(List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2)), List.copyOf(m.keySet()));
        assertEquals(new BigDecimal("40.00"), m.get(YearMonth.of(2026, 1)));
        assertEquals(new BigDecimal("31.00"), m.get(YearMonth.of(2026, 2)));
    }

    @Test
    void resultsAreNotMutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> Analytics.splitAtThreshold(orders, BigDecimal.ONE).get(true).clear());
    }

    @Test
    void allOfAndAnyOfAndNoneOf() {
        Predicate<Integer> pos = n -> n > 0;
        Predicate<Integer> even = n -> n % 2 == 0;
        assertTrue(Rules.allOf(List.of(pos, even)).test(4));
        assertFalse(Rules.allOf(List.of(pos, even)).test(3));
        assertTrue(Rules.anyOf(List.of(pos, even)).test(-2));
        assertFalse(Rules.anyOf(List.of(pos, even)).test(-3));
        assertTrue(Rules.noneOf(List.of(pos, even)).test(-3));
        assertFalse(Rules.noneOf(List.of(pos, even)).test(2));
    }

    @Test
    void emptyCombinatorsFollowLogicConventions() {
        assertTrue(Rules.<String>allOf(List.of()).test("x"));
        assertFalse(Rules.<String>anyOf(List.of()).test("x"));
        assertTrue(Rules.<String>noneOf(List.of()).test("x"));
    }

    @Test
    void predicatesSnapshotTheirInput() {
        List<Predicate<Integer>> list = new ArrayList<>();
        list.add(n -> n > 0);
        Predicate<Integer> p = Rules.allOf(list);
        list.add(n -> false);
        assertTrue(p.test(1));
        assertThrows(NullPointerException.class, () -> Rules.allOf(null));
    }

    @Test
    void rulesFilterOrdersInStream() {
        Predicate<Order> big = x -> x.amount().compareTo(new BigDecimal("10")) >= 0;
        Predicate<Order> ann = x -> x.customer().equals("ann");
        assertEquals(3, orders.stream().filter(Rules.allOf(List.of(big, ann))).count());
        assertEquals(List.of("bob", "bob"), orders.stream().filter(Rules.noneOf(List.of(big)))
                .map(Order::customer).collect(Collectors.toList()));
    }
}
