package bench.orders;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Order(String customer, BigDecimal amount, LocalDate date) {}
