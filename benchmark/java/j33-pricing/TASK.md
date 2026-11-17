# j33-pricing: Extend legacy pricing without changing it

Java 21, standard library only. Put your solution under `bench-solutions/java/j33-pricing/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.pricing;
public final class Pricing {
    public static Quote quote(java.util.List<String> lines, String customer, String coupon);

    public record Quote(int items, long subtotal, long tax, long shipping, long total) {
        public String format();
    }
}
```

You are replacing the legacy method below, which works and is relied on exactly as it behaves today, quirks included.

```java
static String quote(List<String> lines, String customer, String coupon) {
    long subtotal = 0;
    long taxable = 0;
    int items = 0;
    for (String raw : lines) {
        String[] f = raw.split(",");
        if (f.length != 3) continue;
        String sku = f[0].trim();
        int qty;
        long unit;
        try {
            qty = Integer.parseInt(f[1].trim());
            unit = Long.parseLong(f[2].trim());
        } catch (NumberFormatException e) {
            continue;
        }
        if (qty <= 0 || unit < 0 || sku.isEmpty()) continue;
        long line = qty * unit;
        if (qty >= 10) line = line - line / 20;
        if (sku.startsWith("C-") && qty >= 3) line = line - unit;
        subtotal += line;
        if (!sku.startsWith("B-")) taxable += line;
        items += qty;
    }
    if ("GOLD".equals(customer)) {
        subtotal -= Math.round(subtotal * 0.10);
        taxable -= Math.round(taxable * 0.10);
    } else if ("SILVER".equals(customer) && items >= 5) {
        subtotal -= subtotal * 5 / 100;
        taxable -= taxable * 5 / 100;
    }
    if ("SAVE5".equals(coupon) && subtotal >= 5000) {
        subtotal -= 500;
        taxable = Math.max(0, taxable - 500);
    } else if (coupon != null && coupon.startsWith("PCT")) {
        try {
            int pct = Integer.parseInt(coupon.substring(3));
            if (pct >= 1 && pct <= 50) {
                subtotal -= subtotal * pct / 100;
                taxable -= taxable * pct / 100;
            }
        } catch (NumberFormatException e) {
            // ignore a bad coupon
        }
    }
    long tax = (taxable * 15 + 50) / 100;
    long shipping = items == 0 || subtotal >= 10000 || "GOLD".equals(customer) ? 0 : 499;
    long total = subtotal + tax + shipping;
    return "items=" + items + ";subtotal=" + subtotal + ";tax=" + tax
            + ";shipping=" + shipping + ";total=" + total;
}
```

## Requirements

- **Same results:** for every input the legacy method accepts, `Pricing.quote(...).format()` must return exactly the string the legacy method returns. The two new rules below are the only exceptions. The record's fields hold the same five numbers as the string.
- **New rule: multi-buy.** On a line whose SKU (after trimming) starts with `"M-"`, every third unit is free: the line is charged for `qty - qty / 3` units. The bulk discount (`qty >= 10`) is still decided by `qty`, and applies to the reduced charge. `items` still counts all `qty` units. Multi-buy lines are taxable.
- **New rule: PLATINUM customers.** Customer `"PLATINUM"` (exact match) gets 15% off, rounded the same way as GOLD's 10% (applied to both subtotal and taxable), and free shipping like GOLD. Coupons then apply as usual.
- **Null inputs:** a null `lines` list or a null element in it throws `IllegalArgumentException`. A null `customer` or `coupon` is allowed, as before.
