package ae;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** An immutable purchase. */
public record Order(String customer, String category, BigDecimal amount, LocalDate date) {
    public Order {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
    }
}
