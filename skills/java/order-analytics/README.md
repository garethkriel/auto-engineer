# order-analytics

- **Description:** Custom `Collector` built from an immutable monoid (`Stats`: EMPTY, accept, combine), grouping/partitioning with sorted maps, BigDecimal mean rounding, and predicate combinators (`allOf/anyOf/noneOf`) that snapshot their input.
- **Language:** java
- **Tags:** streams, collectors, Collector.of, groupingBy, partitioningBy, BigDecimal, immutability, Predicate
- **Source round:** 20261114T1058Z
- **Tests:** 22, `mvn -q -B test`
