package bench.orders;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A customer order. */
public record Order(String customer, BigDecimal amount, LocalDate date) { }
