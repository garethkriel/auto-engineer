# j19-allocate: Proportional money allocation

Java 21, standard library only. Put your solution under `bench-solutions/java/j19-allocate/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.money;
public final class Allocator {
    public static java.util.List<java.math.BigDecimal> allocate(java.math.BigDecimal amount, int[] weights, int scale);
}
```

Split `amount` into one part per weight, in proportion to the weights. The parts must add up to exactly `amount`, and every part has exactly `scale` decimal places (`BigDecimal.scale() == scale`). Use the **largest remainder** method, in units of 10^-scale:

1. Compute each part's exact share, `amount × w / Σw`, and cut it toward zero to whole units.
2. Hand out the units still missing, one each, to the parts with the largest cut-off remainder. Compare remainders by absolute size, and break ties in favour of the lower index.
3. For a negative amount, the same happens with negative units, so `-100.00` split three ways gives `[-33.34, -33.33, -33.33]`.

A part whose weight is 0 is always exactly zero.

Examples:
- `100.00` split `[1,1,1]` at scale 2 gives `[33.34, 33.33, 33.33]`.
- `0.05` split `[3,7]` at scale 2 gives `[0.02, 0.03]`.

**Errors:** each of these throws `IllegalArgumentException`:
- a null amount or null weights;
- empty weights, a negative weight, or all weights zero;
- `scale < 0`;
- an `amount` with more decimal places than `scale` (ignoring trailing zeros).
