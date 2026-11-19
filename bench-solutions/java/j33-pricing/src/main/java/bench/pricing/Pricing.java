package bench.pricing;

import java.util.List;

/** Pricing: a structured replacement for the legacy quote method, plus multi-buy and PLATINUM rules. */
public final class Pricing {
    private Pricing() { }

    public record Quote(int items, long subtotal, long tax, long shipping, long total) {
        public String format() {
            return "items=" + items + ";subtotal=" + subtotal + ";tax=" + tax
                    + ";shipping=" + shipping + ";total=" + total;
        }
    }

    public static Quote quote(List<String> lines, String customer, String coupon) {
        if (lines == null) throw new IllegalArgumentException("lines is null");
        long subtotal = 0;
        long taxable = 0;
        int items = 0;
        for (String raw : lines) {
            if (raw == null) throw new IllegalArgumentException("null line");
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
            long charged = sku.startsWith("M-") ? qty - qty / 3 : qty;
            long line = charged * unit;
            if (qty >= 10) line = line - line / 20;
            if (sku.startsWith("C-") && qty >= 3) line = line - unit;
            subtotal += line;
            if (!sku.startsWith("B-")) taxable += line;
            items += qty;
        }
        if ("GOLD".equals(customer)) {
            subtotal -= Math.round(subtotal * 0.10);
            taxable -= Math.round(taxable * 0.10);
        } else if ("PLATINUM".equals(customer)) {
            subtotal -= Math.round(subtotal * 0.15);
            taxable -= Math.round(taxable * 0.15);
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
        boolean freeShip = "GOLD".equals(customer) || "PLATINUM".equals(customer);
        long shipping = items == 0 || subtotal >= 10000 || freeShip ? 0 : 499;
        long total = subtotal + tax + shipping;
        return new Quote(items, subtotal, tax, shipping, total);
    }
}
