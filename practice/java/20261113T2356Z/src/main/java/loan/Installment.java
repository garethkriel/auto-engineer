package loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of an amortization schedule; money values have scale 2. */
public record Installment(int number, LocalDate dueDate, BigDecimal payment, BigDecimal interest,
        BigDecimal principal, BigDecimal balance) {
}
