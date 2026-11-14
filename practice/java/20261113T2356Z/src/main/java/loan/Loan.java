package loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A fixed-rate loan.
 *
 * @param principal         amount borrowed, strictly positive
 * @param annualRatePercent nominal annual rate in percent (12 means 12%), not negative
 * @param months            number of monthly payments, at least 1
 * @param firstPaymentDate  due date of the first installment
 */
public record Loan(BigDecimal principal, BigDecimal annualRatePercent, int months, LocalDate firstPaymentDate) {

    /** Validating factory; the only supported way to build a loan. */
    public static Loan of(BigDecimal principal, BigDecimal annualRatePercent, int months, LocalDate firstPaymentDate)
            throws InvalidLoanException {
        if (principal == null || annualRatePercent == null || firstPaymentDate == null) {
            throw new InvalidLoanException("arguments must not be null");
        }
        if (principal.signum() <= 0) {
            throw new InvalidLoanException("principal must be positive: " + principal);
        }
        if (annualRatePercent.signum() < 0) {
            throw new InvalidLoanException("rate must not be negative: " + annualRatePercent);
        }
        if (months < 1) {
            throw new InvalidLoanException("months must be at least 1: " + months);
        }
        return new Loan(principal, annualRatePercent, months, firstPaymentDate);
    }
}
